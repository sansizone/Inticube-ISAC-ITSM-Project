package com.inticube.teamboard.service;

import com.inticube.teamboard.config.UserPrincipal;
import com.inticube.teamboard.dao.AppUserDao;
import com.inticube.teamboard.dao.BoardPartsDao;
import com.inticube.teamboard.dao.ItemDao;
import com.inticube.teamboard.dao.Rows;
import com.inticube.teamboard.domain.*;
import com.inticube.teamboard.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.inticube.teamboard.dao.Rows.lng;

/**
 * 텍스트·드롭다운 컬럼 → 연결(RELATION) 컬럼 변환.
 * 값(예: '바로크레디트')을 대상 워크스페이스의 표시값(표시 컬럼 또는 업무명)과 대소문자·공백 무시로 맞춰 업무 ID 로 바꾼다.
 * 값 전체가 안 맞으면 쉼표로 나눠 각각 맞춘다. 못 찾은 값은 대상에 새 업무로 추가하거나, 별도 텍스트 컬럼에 보존한다(데이터 손실 없음).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ColumnConvertService {
    /** 연결 컬럼으로 바꿀 수 있는 유형: 연결을 뺀 전부 (사람·숫자 등도 값이 대상 항목 이름과 같으면 연결, 아니면 보관) */
    private static final Set<String> CONVERTIBLE = Set.of("TEXT", "LONG_TEXT", "DROPDOWN", "PEOPLE", "NUMBER", "DATE", "DATETIME", "LINK");
    private final BoardPartsDao partsDao;
    private final ItemDao itemDao;
    private final AppUserDao userDao;
    private final AccessGuard guard;

    public record Unmatched(String value, long count) {}
    public record Preview(long rows, long matchedRows, long partialRows, long unmatchedRows, List<Unmatched> unmatched, int unmatchedDistinct) {}
    public record Result(long linkedRows, int created, Long keptColumnId, String keptColumnName) {}

    private record Ctx(BoardColumn col, Map<String, Long> index, List<ItemValue> vals) {}

    private static String key(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    private Ctx load(Long columnId, Long refBoardId, Long refColumnId) {
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null) throw ApiException.notFound("컬럼");
        guard.setManageChecked(c.getBoard());
        if (!CONVERTIBLE.contains(c.getType())) throw ApiException.bad("이미 연결 컬럼입니다.");
        if (!"ITEM".equals(c.getScope())) throw ApiException.bad("업무 컬럼만 바꿀 수 있습니다.");
        if (refBoardId == null) throw ApiException.bad("연결할 워크스페이스를 선택하세요.");
        if (refBoardId.equals(c.getBoard().getId())) throw ApiException.bad("같은 워크스페이스로는 연결할 수 없습니다.");
        guard.getBoardChecked(refBoardId);
        if (refColumnId != null) {
            BoardColumn rc = partsDao.selectColumnById(refColumnId);
            if (rc == null) throw ApiException.notFound("표시 컬럼");
            if (!rc.getBoard().getId().equals(refBoardId)) throw ApiException.bad("표시 컬럼은 연결할 워크스페이스의 컬럼이어야 합니다.");
        }
        Map<String, Long> index = new HashMap<>();
        Map<Long, String> disp = new HashMap<>();
        if (refColumnId != null) for (Map<String, Object> r : itemDao.selectValueTuplesByColumn(refColumnId)) disp.put(lng(r, "item_id"), Rows.str(r, "val_text"));
        List<Map<String, Object>> rows = itemDao.selectPickRows(refBoardId);
        for (Map<String, Object> r : rows) { String d = disp.get(lng(r, "id")); if (d != null && !d.isBlank()) index.putIfAbsent(key(d), lng(r, "id")); }
        for (Map<String, Object> r : rows) index.putIfAbsent(key(Rows.str(r, "item_name")), lng(r, "id")); // 업무명으로도 찾기
        return new Ctx(c, index, itemDao.selectValuesByColumns(List.of(columnId)));
    }

    /** 값 → (찾은 ID, 못 찾은 조각) */
    private static Map.Entry<List<Long>, List<String>> match(String v, Map<String, Long> index) {
        List<Long> ids = new ArrayList<>();
        List<String> miss = new ArrayList<>();
        Long whole = index.get(key(v));
        if (whole != null) { ids.add(whole); return Map.entry(ids, miss); }
        for (String part : v.split("[,;\\n]")) {   // 사람 컬럼처럼 여러 값(쉼표·세미콜론·줄바꿈 구분)
            String p = part.trim();
            if (p.isEmpty()) continue;
            Long id = index.get(key(p));
            if (id != null) { if (!ids.contains(id)) ids.add(id); } else if (!miss.contains(p)) miss.add(p);
        }
        return Map.entry(ids, miss);
    }

    @Transactional(readOnly = true)
    public Preview getPreview(Long columnId, Long refBoardId, Long refColumnId) {
        Ctx ctx = load(columnId, refBoardId, refColumnId);
        long rows = 0, ok = 0, partial = 0, none = 0;
        Map<String, Long> missCount = new LinkedHashMap<>();
        for (ItemValue v : ctx.vals()) {
            if (v.getValue() == null || v.getValue().isBlank()) continue;
            rows++;
            var m = match(v.getValue(), ctx.index());
            if (m.getValue().isEmpty()) ok++; else if (m.getKey().isEmpty()) none++; else partial++;
            for (String s : m.getValue()) missCount.merge(s, 1L, Long::sum);
        }
        List<Unmatched> list = missCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(300).map(e -> new Unmatched(e.getKey(), e.getValue())).toList();
        return new Preview(rows, ok, partial, none, list, missCount.size());
    }

    public Result updateConvert(Long columnId, Long refBoardId, Long refColumnId, boolean createMissing, UserPrincipal me) {
        Ctx ctx = load(columnId, refBoardId, refColumnId);
        BoardColumn c = ctx.col();
        Map<String, Long> index = new HashMap<>(ctx.index());
        int created = 0;
        BoardColumn kept = null;
        if (createMissing) {
            // 못 찾은 값을 대상 워크스페이스 첫 그룹에 새 업무로 추가
            Set<String> missing = new LinkedHashSet<>();
            for (ItemValue v : ctx.vals()) if (v.getValue() != null) missing.addAll(match(v.getValue(), index).getValue());
            if (!missing.isEmpty()) {
                BoardGroup g = partsDao.selectGroupsByBoard(refBoardId).stream().findFirst()
                        .orElseThrow(() -> ApiException.bad("연결할 워크스페이스에 그룹이 없습니다."));
                BoardStatus st = partsDao.selectStatusesByBoard(refBoardId).stream().findFirst().orElse(null);
                int order = itemDao.selectMaxSort(g.getId());
                AppUser actor = userDao.selectById(me.id());
                for (String name : missing) {
                    if (index.containsKey(key(name))) continue;
                    Item it = new Item();
                    it.setBoard(g.getBoard());
                    it.setGroup(g);
                    it.setName(name.length() > 300 ? name.substring(0, 300) : name);
                    it.setStatus(st);
                    it.setCreatedBy(actor);
                    it.setSortOrder(++order);
                    itemDao.insert(it);
                    if (refColumnId != null) {
                        ItemValue dv = new ItemValue();
                        dv.setItemId(it.getId()); dv.setColumnId(refColumnId); dv.setValue(name);
                        itemDao.insertValue(dv);
                    }
                    index.put(key(name), it.getId());
                    created++;
                }
            }
        }
        long linked = 0;
        List<ItemValue> keepVals = new ArrayList<>();
        for (ItemValue v : ctx.vals()) {
            var m = v.getValue() == null ? Map.entry(List.<Long>of(), List.<String>of()) : match(v.getValue(), index);
            if (!m.getValue().isEmpty()) {
                ItemValue kv = new ItemValue();
                kv.setItemId(v.getItemId()); kv.setValue(String.join(", ", m.getValue()));
                keepVals.add(kv);
            }
            if (m.getKey().isEmpty()) { itemDao.deleteValue(v.getId()); continue; }
            v.setValue(m.getKey().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
            itemDao.updateValue(v);
            linked++;
        }
        if (!keepVals.isEmpty()) {
            // 연결 못 한 값은 숨긴 텍스트 컬럼에 그대로 보존
            kept = new BoardColumn();
            kept.setBoard(c.getBoard());
            kept.setName(c.getName() + " (연결 안 된 값)");
            kept.setType("TEXT");
            kept.setScope("ITEM");
            kept.setVisible(false);
            kept.setSortOrder(partsDao.selectColumnMaxSort(c.getBoard().getId()) + 1);
            partsDao.insertColumn(kept);
            for (ItemValue kv : keepVals) kv.setColumnId(kept.getId());
            itemDao.batchInsertValues(keepVals);
        }
        c.setType(RelationService.TYPE);
        c.setRefBoardId(refBoardId);
        c.setRefColumnId(refColumnId);
        partsDao.updateColumn(c);
        return new Result(linked, created, kept == null ? null : kept.getId(), kept == null ? null : kept.getName());
    }
}

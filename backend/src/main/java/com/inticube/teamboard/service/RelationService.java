package com.inticube.teamboard.service;

import com.inticube.teamboard.dao.BoardDao;
import com.inticube.teamboard.dao.BoardPartsDao;
import com.inticube.teamboard.dao.ItemDao;
import com.inticube.teamboard.dao.Rows;
import com.inticube.teamboard.domain.Board;
import com.inticube.teamboard.domain.BoardColumn;
import com.inticube.teamboard.web.ApiException;
import com.inticube.teamboard.web.dto.Dtos.Backlink;
import com.inticube.teamboard.web.dto.Dtos.PickOption;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static com.inticube.teamboard.dao.Rows.lng;

/**
 * 연결(RELATION) 컬럼: 다른 워크스페이스의 업무를 값으로 가진다. 값 = 업무 ID 쉼표 구분.
 * 표시값은 대상 워크스페이스의 표시 컬럼(없으면 업무명).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RelationService {
    public static final String TYPE = "RELATION";
    private final ItemDao itemDao;
    private final BoardPartsDao partsDao;
    private final BoardDao boardDao;
    private final AccessGuard guard;

    /** 쉼표 구분 ID 문자열 → ID 목록 (중복 제거, 잘못된 값 무시) */
    public static List<Long> getIds(String v) {
        List<Long> out = new ArrayList<>();
        if (v == null || v.isBlank()) return out;
        for (String p : v.split(",")) {
            try { Long id = Long.parseLong(p.trim()); if (!out.contains(id)) out.add(id); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** 컬럼 ID → (업무 ID → 표시값). colValues: 컬럼 ID → 그 컬럼의 값들 */
    public Map<Long, Map<Long, String>> getLabels(Collection<BoardColumn> relCols, Map<Long, List<String>> colValues) {
        Map<Long, Map<Long, String>> out = new HashMap<>();
        Map<Long, Set<Long>> idsByCol = new HashMap<>();
        Set<Long> all = new HashSet<>();
        for (BoardColumn c : relCols) {
            Set<Long> ids = new LinkedHashSet<>();
            for (String v : colValues.getOrDefault(c.getId(), List.<String>of())) ids.addAll(getIds(v));
            if (ids.isEmpty()) continue;
            idsByCol.put(c.getId(), ids);
            all.addAll(ids);
        }
        if (all.isEmpty()) return out;
        Map<Long, String> names = selectNames(all);
        for (BoardColumn c : relCols) {
            Set<Long> ids = idsByCol.get(c.getId());
            if (ids == null) continue;
            Map<Long, String> disp = new HashMap<>();
            if (c.getRefColumnId() != null)
                for (List<Long> chunk : chunks(ids))
                    for (Map<String, Object> r : itemDao.selectValueTuplesByColumnAndItems(c.getRefColumnId(), chunk)) disp.put(lng(r, "item_id"), Rows.str(r, "val_text"));
            Map<Long, String> m = new HashMap<>();
            for (Long id : ids) {
                if (!names.containsKey(id)) { m.put(id, "(삭제된 업무 #" + id + ")"); continue; }
                String d = disp.get(id);
                m.put(id, d == null || d.isBlank() ? names.get(id) : d);
            }
            out.put(c.getId(), m);
        }
        return out;
    }

    /** 연결 컬럼 선택 후보 검색 (대상 워크스페이스의 상위 업무) */
    public List<PickOption> getOptions(Long columnId, String q, int limit) {
        BoardColumn c = partsDao.selectColumnById(columnId);
        if (c == null) throw ApiException.notFound("컬럼");
        if (!TYPE.equals(c.getType()) || c.getRefBoardId() == null) throw ApiException.bad("연결 컬럼이 아닙니다.");
        Map<Long, String> disp = new HashMap<>();
        if (c.getRefColumnId() != null)
            for (Map<String, Object> r : itemDao.selectValueTuplesByColumn(c.getRefColumnId())) disp.put(lng(r, "item_id"), Rows.str(r, "val_text"));
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<PickOption> out = new ArrayList<>();
        for (Map<String, Object> r : itemDao.selectPickRows(c.getRefBoardId())) {
            Long id = lng(r, "id");
            String name = Rows.str(r, "item_name");
            String group = Rows.str(r, "group_name");
            String d = disp.get(id);
            String label = d == null || d.isBlank() ? name : d;
            String sub = label.equals(name) ? group : name + " · " + group;
            if (!needle.isEmpty() && !label.toLowerCase().contains(needle) && !name.toLowerCase().contains(needle) && !String.valueOf(id).equals(needle)) continue;
            out.add(new PickOption(id, label, sub));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** 이 업무를 참조하는 다른 업무 (볼 수 있는 워크스페이스만) */
    public List<Backlink> getBacklinks(Long itemId, Long boardId) {
        List<BoardColumn> refCols = partsDao.selectColumnsByRefBoardAndType(boardId, TYPE);
        if (refCols.isEmpty()) return List.of();
        Map<Long, BoardColumn> byId = new HashMap<>();
        refCols.forEach(c -> byId.put(c.getId(), c));
        Map<Long, List<Long>> hits = new LinkedHashMap<>();
        for (Map<String, Object> r : itemDao.selectValueTuplesLike(byId.keySet(), "%" + itemId + "%")) {
            if (getIds(Rows.str(r, "val_text")).contains(itemId)) hits.computeIfAbsent(lng(r, "column_id"), k -> new ArrayList<>()).add(lng(r, "item_id"));
        }
        if (hits.isEmpty()) return List.of();
        Set<Long> visible = guard.getVisibleBoardIds();
        Set<Long> all = new HashSet<>();
        hits.values().forEach(all::addAll);
        Map<Long, String> names = selectNames(all);
        List<Backlink> out = new ArrayList<>();
        for (var e : hits.entrySet()) {
            BoardColumn c = byId.get(e.getKey());
            Board b = c.getBoard();
            if (!AccessGuard.isVisible(b, visible)) continue;
            List<PickOption> list = e.getValue().stream().filter(names::containsKey).map(id -> new PickOption(id, names.get(id), null)).toList();
            if (!list.isEmpty()) out.add(new Backlink(c.getId(), c.getName(), b.getId(), b.getName(), list));
        }
        return out;
    }

    /** 연결 컬럼들이 가리키는 워크스페이스 ID → 이름 */
    public Map<Long, String> getBoardNames(Collection<BoardColumn> relCols) {
        Map<Long, String> out = new HashMap<>();
        for (BoardColumn c : relCols)
            if (c.getRefBoardId() != null && !out.containsKey(c.getRefBoardId())) {
                Board b = boardDao.selectById(c.getRefBoardId());
                if (b != null) out.put(b.getId(), b.getName());
            }
        return out;
    }

    private Map<Long, String> selectNames(Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        for (List<Long> chunk : chunks(ids)) for (Map<String, Object> r : itemDao.selectNames(chunk)) names.put(lng(r, "id"), Rows.str(r, "item_name"));
        return names;
    }

    private static List<List<Long>> chunks(Collection<Long> ids) {
        List<Long> list = new ArrayList<>(ids);
        List<List<Long>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += 1000) out.add(list.subList(i, Math.min(list.size(), i + 1000)));
        return out;
    }
}

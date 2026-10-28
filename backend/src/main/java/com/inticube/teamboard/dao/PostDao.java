package com.inticube.teamboard.dao;

import com.inticube.teamboard.domain.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface PostDao {
    List<PostCategory> selectCategories();
    PostCategory selectCategoryById(@Param("id") Long id);
    long selectCategoryCount();
    int selectCategoryMaxSort();
    int insertCategory(PostCategory c);
    int updateCategory(PostCategory c);
    int deleteCategory(@Param("id") Long id);

    Post selectPostById(@Param("id") Long id);
    List<Post> selectPosts(@Param("categoryId") Long categoryId, @Param("q") String q, @Param("excludePinned") boolean excludePinned, @Param("offset") int offset, @Param("limit") int limit);
    long selectPostCount(@Param("categoryId") Long categoryId, @Param("q") String q, @Param("excludePinned") boolean excludePinned);
    List<Post> selectPinned(@Param("categoryId") Long categoryId);
    List<Map<String, Object>> selectCountByCategory();
    long selectCountInCategory(@Param("categoryId") Long categoryId);
    Post selectPrev(@Param("categoryId") Long categoryId, @Param("id") Long id);
    Post selectNext(@Param("categoryId") Long categoryId, @Param("id") Long id);
    int insertPost(Post p);
    int updatePost(Post p);
    int updateViewCount(@Param("id") Long id);
    int deletePost(@Param("id") Long id);

    PostComment selectCommentById(@Param("id") Long id);
    List<PostComment> selectCommentsByPost(@Param("postId") Long postId);
    boolean selectReplyExists(@Param("parentId") Long parentId);
    List<Map<String, Object>> selectCommentCountByPosts(@Param("ids") Collection<Long> ids);
    int insertComment(PostComment c);
    int updateComment(PostComment c);
    int updateCommentContent(PostComment c);
    int deleteComment(@Param("id") Long id);
    int deleteCommentsByPost(@Param("postId") Long postId);

    PostAttachment selectAttachmentById(@Param("id") Long id);
    List<PostAttachment> selectAttachmentsByPost(@Param("postId") Long postId);
    List<Map<String, Object>> selectAttachmentCountByPosts(@Param("ids") Collection<Long> ids);
    int insertAttachment(PostAttachment a);
    int deleteAttachment(@Param("id") Long id);
    int deleteAttachmentsByPost(@Param("postId") Long postId);
}

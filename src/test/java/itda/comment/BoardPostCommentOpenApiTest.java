package itda.comment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BoardPostCommentOpenApiTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void runtimeOpenApiExposesCommentCreateListUpdateAndDeleteContracts() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].post").exists())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].get").exists())
                .andExpect(jsonPath("$.paths['/comments/{parentCommentId}/replies'].post").exists())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/comments/{parentCommentId}/replies'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/comments/{parentCommentId}/replies'].post.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/comments/{commentId}'].patch").exists())
                .andExpect(jsonPath("$.paths['/comments/{commentId}'].delete").exists())
                .andExpect(jsonPath("$.paths['/comments/{commentId}'].patch.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/comments/{commentId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].post.requestBody.content['application/json'].schema").exists())
                .andExpect(jsonPath("$.paths['/comments/{commentId}'].patch.requestBody.content['application/json'].schema").exists())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].post.responses['201'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/ApiResponseCommentResponse"))
                .andExpect(jsonPath("$.paths['/comments/{parentCommentId}/replies'].post.responses['201'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/ApiResponseCommentResponse"))
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].get.responses['200'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/ApiResponseCommentListResponse"))
                .andExpect(jsonPath("$.paths['/comments/{commentId}'].patch.responses['200'].content['*/*'].schema.$ref")
                        .value("#/components/schemas/ApiResponseCommentResponse"))
                .andExpect(jsonPath("$.components.schemas.ApiResponseCommentResponse.properties.data.$ref")
                        .value("#/components/schemas/CommentResponse"))
                .andExpect(jsonPath("$.components.schemas.ApiResponseCommentListResponse.properties.data.$ref")
                        .value("#/components/schemas/CommentListResponse"))
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.commentId").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.postId").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.parentCommentId").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.depth").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.authorPet").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.content").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.version").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.createdAt").exists())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.updatedAt").exists())
                .andExpect(jsonPath("$.components.schemas.CommentListResponse.properties.items").exists())
                .andExpect(jsonPath("$.components.schemas.CommentListResponse.properties.page").exists())
                .andExpect(jsonPath("$.components.schemas.CommentTreeResponse.properties.replies").exists())
                .andExpect(jsonPath("$.components.schemas.CommentTreeResponse.properties.replies.items.$ref")
                        .value("#/components/schemas/CommentTreeResponse"))
                .andExpect(jsonPath("$.components.schemas.CommentTreeResponse.properties.deleted").exists())
                .andExpect(jsonPath("$.components.schemas.CommentTreeResponse.properties.parentCommentId").exists())
                .andExpect(jsonPath("$.components.schemas.CommentTreeResponse.properties.depth").exists())
                .andExpect(jsonPath("$.paths['/posts/{postId}/comments'].get.parameters[*].name")
                        .value(org.hamcrest.Matchers.hasItems("cursor", "size")));
    }
}

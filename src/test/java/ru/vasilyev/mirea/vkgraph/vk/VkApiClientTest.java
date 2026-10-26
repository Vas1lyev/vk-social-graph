package ru.vasilyev.mirea.vkgraph.vk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VkApiClientTest {
    @Test
    void buildsExecuteBatchForFriends() {
        String code = VkApiClient.buildFriendsBatchCode(List.of(10L, 20L));

        assertThat(code).isEqualTo("return [API.friends.get({\"user_id\":10,\"count\":5000}),"
                + "API.friends.get({\"user_id\":20,\"count\":5000})];");
    }
}

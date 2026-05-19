package com.sanyan.character.web;

import com.sanyan.character.CharacterApi;
import com.sanyan.character.dto.RelationshipDto;
import com.sanyan.common.auth.LoginUser;
import com.sanyan.common.web.BaseResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关系状态查询接口。
 *
 * <p>当前 MVP 单角色阶段，默认 character_id=1（小婉）；多角色 Plan 5+ 时改成路径参数 / query string。
 */
@RestController
@RequestMapping("/api/relationships")
@RequiredArgsConstructor
public class RelationshipController {

    /** MVP 单角色：硬编码小婉的 character_id（V1 baseline seed 的 id=1）。 */
    private static final Long DEFAULT_CHARACTER_ID = 1L;

    private final CharacterApi characterApi;

    @GetMapping("/me")
    public BaseResp<RelationshipDto> fetchMyRelationship(@LoginUser Long userId) {
        return BaseResp.success(characterApi.fetchMyRelationship(userId, DEFAULT_CHARACTER_ID));
    }
}

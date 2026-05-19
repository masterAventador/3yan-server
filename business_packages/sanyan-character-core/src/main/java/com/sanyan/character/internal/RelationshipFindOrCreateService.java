package com.sanyan.character.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 唯一懒创建 Relationship 入口。
 *
 * <p>调用方：CharacterApiImpl（H4）/ RelationshipFetchService（H3）/
 * MessagePersistedListener（G2 暂时 inline 实现，未来可重构走本 service）
 */
@Service
@RequiredArgsConstructor
public class RelationshipFindOrCreateService {

    private final RelationshipRepository repo;

    @Transactional
    public RelationshipEntity findOrCreate(Long userId, Long characterId) {
        return repo.findByUserIdAndCharacterId(userId, characterId).orElseGet(() -> {
            RelationshipEntity r = new RelationshipEntity();
            r.setUserId(userId);
            r.setCharacterId(characterId);
            return repo.save(r);
        });
    }
}

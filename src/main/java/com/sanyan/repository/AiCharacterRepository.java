package com.sanyan.repository;

import com.sanyan.entity.AiCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiCharacterRepository extends JpaRepository<AiCharacter, Long> {
    List<AiCharacter> findByType(String type);
}

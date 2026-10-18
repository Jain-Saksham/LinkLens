package com.linklens.repository;

import com.linklens.domain.ShortUrl;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Atomic in-database increment. A read-modify-write in Java would lose clicks under
     * concurrent redirects of the same link.
     */
    @Modifying
    @Query("update ShortUrl s set s.clickCount = s.clickCount + 1 where s.id = :id")
    int incrementClickCount(@Param("id") Long id);
}

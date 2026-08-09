package ru.abs7.videooffer.bitrix.mobile.upload;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MobileVideoUploadRepository extends JpaRepository<MobileVideoUpload, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select upload from MobileVideoUpload upload where upload.id = :id")
    Optional<MobileVideoUpload> findByIdForUpdate(@Param("id") UUID id);

    List<MobileVideoUpload> findAllByExpiresAtBefore(OffsetDateTime moment);
}

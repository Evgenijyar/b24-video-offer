package ru.abs7.videooffer.bitrix;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BitrixInstallationRepository extends JpaRepository<BitrixInstallation, Long> {
    Optional<BitrixInstallation> findByMemberId(String memberId);
}

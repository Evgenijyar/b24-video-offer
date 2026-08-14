package ru.abs7.videooffer.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.abs7.videooffer.bitrix.mobile.upload.MobileVideoUploadRepository;
import ru.abs7.videooffer.offer.VideoOffer;
import ru.abs7.videooffer.offer.VideoOfferRepository;
import ru.abs7.videooffer.offer.VideoOfferStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Serializes storage admission per tenant while keeping the actual long-running
 * video work outside database transactions. Reservations live on the durable
 * offer/upload rows, so a process restart cannot lose quota accounting.
 */
@Service
public class TenantStorageQuotaService {
    private static final Logger log = LoggerFactory.getLogger(TenantStorageQuotaService.class);

    private final VideoOfferTenantRepository tenantRepository;
    private final VideoOfferRepository offerRepository;
    private final MobileVideoUploadRepository uploadRepository;
    private final Path pageBuilderRoot;

    public TenantStorageQuotaService(
            VideoOfferTenantRepository tenantRepository,
            VideoOfferRepository offerRepository,
            MobileVideoUploadRepository uploadRepository,
            @Value("${app.page-builder.storage-dir:./data/page-builder}") String pageBuilderStorageDir) {
        this.tenantRepository = tenantRepository;
        this.offerRepository = offerRepository;
        this.uploadRepository = uploadRepository;
        this.pageBuilderRoot = Path.of(pageBuilderStorageDir).toAbsolutePath().normalize();
    }

    /** Advisory check used before a potentially large upload starts. */
    @Transactional
    public void ensureAvailable(Long tenantId, long additionalBytes) {
        if (tenantId == null || tenantId <= 0 || additionalBytes <= 0) return;
        VideoOfferTenant tenant = lockTenant(tenantId);
        assertCapacityLocked(tenant, additionalBytes, 0L);
    }

    /**
     * Durable reservation for a downloaded URL/Kontur/YouTube offer. The tenant
     * row lock makes admission atomic across all users of the same company.
     */
    @Transactional
    public void reserveForOffer(Long tenantId, UUID offerId, long bytes) {
        if (tenantId == null || tenantId <= 0 || bytes <= 0) return;
        VideoOfferTenant tenant = lockTenant(tenantId);
        VideoOffer offer = offerRepository.findByIdForUpdate(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Видеооффер не найден"));
        if (!tenantId.equals(offer.getTenantId())) {
            throw new IllegalArgumentException("Видеооффер относится к другой компании");
        }
        long existingReservation = offer.getStorageReservedBytes();
        assertCapacityLocked(tenant, bytes, existingReservation);
        offer.reserveStorage(bytes);
        offerRepository.saveAndFlush(offer);
        log.info("Tenant storage reserved for offer: tenantId={}, offerId={}, bytes={}, readyBytes={}, reservedBytes={}",
                tenantId, offerId, bytes, readyBytesLocked(tenant), reservedBytesLocked(tenant));
    }

    /**
     * Called from the atomic mobile-upload claim transaction. The caller writes
     * the reservation to the locked upload row before that transaction commits.
     */
    @Transactional
    public void assertCanReserve(Long tenantId, long bytes, long replacingReservationBytes) {
        if (tenantId == null || tenantId <= 0 || bytes <= 0) return;
        VideoOfferTenant tenant = lockTenant(tenantId);
        assertCapacityLocked(tenant, bytes, Math.max(0L, replacingReservationBytes));
    }

    /**
     * Atomically converts an offer reservation into READY storage. The tenant
     * accounting lock is intentionally held during the transition: quota
     * admission reads READY bytes and reservations with separate SQL statements
     * under READ_COMMITTED, so without this lock it could observe half of the
     * reservation -> READY transition.
     */
    @Transactional
    public VideoOffer markDownloadedOfferReady(UUID offerId, String path, long size, String quality) {
        VideoOffer snapshot = offerRepository.findById(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Видеооффер не найден"));
        Long tenantId = snapshot.getTenantId();
        lockTenantAccounting(tenantId);

        VideoOffer offer = offerRepository.findByIdForUpdate(offerId)
                .orElseThrow(() -> new IllegalArgumentException("Видеооффер не найден"));
        if (tenantId != null && tenantId > 0 && !tenantId.equals(offer.getTenantId())) {
            throw new IllegalStateException("Компания видеооффера изменилась во время обработки");
        }
        if (offer.getTenantId() != null && offer.getTenantId() > 0
                && offer.getStorageReservedBytes() < size) {
            throw new IllegalStateException("Размер готового видео превышает зарезервированный объём хранилища");
        }
        offer.markReady(path, size, quality);
        return offerRepository.saveAndFlush(offer);
    }

    /**
     * Serializes all mutations that affect storage accounting for one tenant.
     * When called from an existing TransactionTemplate/@Transactional block the
     * pessimistic row lock stays held until that outer transaction commits.
     */
    @Transactional
    public void lockTenantAccounting(Long tenantId) {
        if (tenantId == null || tenantId <= 0) return;
        lockTenant(tenantId);
    }

    public long currentReadyBytes(Long tenantId) {
        if (tenantId == null || tenantId <= 0) return 0L;
        VideoOfferTenant tenant = tenantRepository.findById(tenantId).orElse(null);
        return tenant == null ? 0L : readyBytesLocked(tenant);
    }

    public long currentReservedBytes(Long tenantId) {
        if (tenantId == null || tenantId <= 0) return 0L;
        Long offerValue = offerRepository.sumReservedStorageByTenantId(tenantId);
        Long uploadValue = uploadRepository.sumReservedStorageByTenantId(tenantId);
        return safe(offerValue) + safe(uploadValue);
    }

    private VideoOfferTenant lockTenant(Long tenantId) {
        return tenantRepository.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Компания Video Offer не найдена"));
    }

    private void assertCapacityLocked(VideoOfferTenant tenant, long requestedBytes, long replacingReservationBytes) {
        long used = readyBytesLocked(tenant);
        long reserved = reservedBytesLocked(tenant);
        long effectiveReserved = Math.max(0L, reserved - Math.max(0L, replacingReservationBytes));
        long quota = tenant.getDiskQuotaBytes();
        if (requestedBytes > quota || used + effectiveReserved > quota - requestedBytes) {
            throw new IllegalArgumentException("Недостаточно места в хранилище компании. Использовано "
                    + humanBytes(used) + ", зарезервировано " + humanBytes(effectiveReserved)
                    + " из " + humanBytes(quota));
        }
    }

    private long readyBytesLocked(VideoOfferTenant tenant) {
        Long tenantValue = offerRepository.sumReadyStorageByTenantId(tenant.getId(), VideoOfferStatus.READY);
        long total = safe(tenantValue);
        if (tenant.getMemberId() != null && !tenant.getMemberId().isBlank()) {
            total += safe(offerRepository.sumLegacyReadyStorageByMemberId(
                    tenant.getMemberId(), VideoOfferStatus.READY));
        }
        total += persistentPageBytes(tenant.getId());
        return total;
    }

    private long persistentPageBytes(long tenantId) {
        return directoryBytes(pageBuilderRoot.resolve("assets").resolve(Long.toString(tenantId)))
                + directoryBytes(pageBuilderRoot.resolve("attachments").resolve(Long.toString(tenantId)));
    }

    private long directoryBytes(Path directory) {
        if (!Files.isDirectory(directory)) return 0L;
        long total = 0L;
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                try {
                    total = Math.addExact(total, Files.size(path));
                } catch (ArithmeticException error) {
                    return Long.MAX_VALUE;
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return total;
    }

    private long reservedBytesLocked(VideoOfferTenant tenant) {
        return safe(offerRepository.sumReservedStorageByTenantId(tenant.getId()))
                + safe(uploadRepository.sumReservedStorageByTenantId(tenant.getId()));
    }

    private long safe(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private String humanBytes(long value) {
        double mb = value / 1024.0 / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f МБ", mb);
        return String.format(Locale.ROOT, "%.2f ГБ", mb / 1024.0);
    }
}

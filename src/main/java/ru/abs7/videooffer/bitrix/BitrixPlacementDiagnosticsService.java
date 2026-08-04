package ru.abs7.videooffer.bitrix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BitrixPlacementDiagnosticsService {
    private static final Logger log = LoggerFactory.getLogger(BitrixPlacementDiagnosticsService.class);

    private final BitrixInstallationRepository repository;
    private final BitrixRestClient restClient;

    public BitrixPlacementDiagnosticsService(
            BitrixInstallationRepository repository,
            BitrixRestClient restClient) {
        this.repository = repository;
        this.restClient = restClient;
    }

    public List<PlacementDiagnostics> diagnoseAll() {
        List<PlacementDiagnostics> result = new ArrayList<>();
        for (BitrixInstallation installation : repository.findAll()) {
            result.add(diagnose(installation.getMemberId()));
        }
        return result;
    }

    public PlacementDiagnostics diagnose(String memberId) {
        BitrixInstallation installation = repository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalStateException(
                        "Установка Bitrix24 не найдена для member_id=" + memberId));

        log.info("Starting Bitrix placement diagnostics: memberId={}, domain={}",
                memberId, installation.getPortalDomain());

        Map<String, DiagnosticCall> calls = new LinkedHashMap<>();
        calls.put("availableCrmPlacements",
                safeCall(memberId, "placement.list", Map.<String, Object>of("SCOPE", "crm")));
        calls.put("availableMobilePlacements",
                safeCall(memberId, "placement.list", Map.<String, Object>of("SCOPE", "mobile")));
        calls.put("availableAllPlacements",
                safeCall(memberId, "placement.list", Map.<String, Object>of("FULL", true)));
        calls.put("registeredPlacements",
                safeCall(memberId, "placement.get", Map.<String, Object>of()));

        PlacementDiagnostics diagnostics = new PlacementDiagnostics(
                OffsetDateTime.now(),
                installation.getPortalDomain(),
                installation.getMemberId(),
                BitrixInstallationService.desiredPlacements(),
                calls);

        log.info("Bitrix placement diagnostics completed: memberId={}, diagnostics={}",
                memberId, diagnostics);
        return diagnostics;
    }

    private DiagnosticCall safeCall(
            String memberId,
            String method,
            Map<String, Object> parameters) {
        try {
            Map<String, Object> response = restClient.call(memberId, method, parameters);
            return DiagnosticCall.success(response);
        } catch (BitrixRestException error) {
            log.warn("Bitrix placement diagnostic REST call failed: memberId={}, method={}, errorCode={}, error={}",
                    memberId, method, error.getErrorCode(), error.getMessage());
            return DiagnosticCall.failure(error.getErrorCode(), error.getMessage());
        } catch (RuntimeException error) {
            log.warn("Bitrix placement diagnostic transport call failed: memberId={}, method={}, error={}",
                    memberId, method, rootMessage(error), error);
            return DiagnosticCall.failure("TRANSPORT_ERROR", rootMessage(error));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    public record PlacementDiagnostics(
            OffsetDateTime generatedAt,
            String domain,
            String memberId,
            List<String> desiredPlacements,
            Map<String, DiagnosticCall> calls) {
    }

    public record DiagnosticCall(
            boolean success,
            Map<String, Object> response,
            String errorCode,
            String errorMessage) {

        private static DiagnosticCall success(Map<String, Object> response) {
            return new DiagnosticCall(true, response, null, null);
        }

        private static DiagnosticCall failure(String errorCode, String errorMessage) {
            return new DiagnosticCall(false, null, errorCode, errorMessage);
        }
    }
}

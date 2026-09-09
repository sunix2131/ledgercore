package dev.sunix.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sunix.ledger.api.LedgerApiModels.CreateTransactionRequest;
import dev.sunix.ledger.api.LedgerApiModels.PostingRequest;
import dev.sunix.ledger.domain.EntrySide;
import jakarta.validation.Validation;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerRequestValidationTest {
    @Test
    void nullPostingIsAValidationErrorBeforeRequestHashing() {
        var request = new CreateTransactionRequest("reference", "description", Arrays.asList(
                new PostingRequest(UUID.randomUUID(), EntrySide.DEBIT, 100, "USD"), null));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isNotEmpty();
        }
    }
}

package io.everytech.bireinvoice.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.everytech.bireinvoice.CasValidator;
import io.everytech.bireinvoice.EisClient;
import io.everytech.bireinvoice.EisCodes;
import io.everytech.bireinvoice.EisCredentials;
import io.everytech.bireinvoice.EisCrypto;
import io.everytech.bireinvoice.EisException;
import io.everytech.bireinvoice.EisIds;
import io.everytech.bireinvoice.InquiryResult;
import io.everytech.bireinvoice.JdkEisTransport;
import io.everytech.bireinvoice.SchemaProblem;
import io.everytech.bireinvoice.SubmitAck;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.Test;

/** The README's Java example, compiled and run, so the Java-facing API stays usable. */
public class JavaUsageTest {
    @Test
    public void javaCallersCanSignInSubmitAndInquire() throws Exception {
        try (MockEisServer mock = new MockEisServer()) {
            mock.start();
            EisCredentials credentials = EisCredentials.fromJson(mock.getCredentials().toJson());
            EisClient client = new EisClient(credentials, new JdkEisTransport(), Clock.systemUTC(), 0L);

            String today = EisCrypto.manilaDatetime(Instant.now()).substring(0, 8);
            String uid = EisIds.eisUniqueId(today, credentials.getEisCertNumber());
            String json = TestInvoices.INSTANCE.json(TestInvoices.INSTANCE.build(credentials.getEisCertNumber(), today,
                EisCodes.TRANS_VATABLE, 300, 100_000, "INV-00000001", null)).replaceAll("\"EisUniqueId\":\"[0-9A-Z]{24}\"", "\"EisUniqueId\":\"" + uid + "\"");

            List<SchemaProblem> problems = CasValidator.validate(json);
            assertTrue(problems.toString(), problems.isEmpty());

            SubmitAck ack = client.submit(List.of(json));
            InquiryResult result = client.inquire(ack.getSubmitId());
            while (result.getProcessStatusCode().equals(EisCodes.PROCESS_IN_PROGRESS)) {
                result = client.inquire(ack.getSubmitId());
            }
            assertEquals("SUC001", result.getProcessedDocuments().get(0).getResultStatusCode());
            assertEquals(uid, result.getProcessedDocuments().get(0).getInvoiceUid());
            assertEquals("Passed", EisCodes.getResultCodes().get("SUC001"));

            EisClient wrong = new EisClient(credentials.copy(credentials.getEisCertNumber(), credentials.getApplicationId(),
                credentials.getApplicationKey(), credentials.getUserId(), "nope", credentials.getEisPublicKey(),
                credentials.getSigningKeyId(), credentials.getSigningPrivateKey(), credentials.getBaseUrl()),
                new JdkEisTransport(), Clock.systemUTC(), 0L);
            try {
                wrong.authenticate();
                fail("expected E23");
            } catch (EisException e) {
                assertEquals("E23", e.getCode());
            }
        }
    }
}

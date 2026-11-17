import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Cho Maven tự chạy các kịch bản ván đấu phòng riêng bằng socket thật, không cần mở client JavaFX.
 * Mỗi test có socket, phòng và clock giả riêng; try-with-resources dọn fixture cả khi fail.
 */
@Timeout(15)
class PrivateFlowCheckTest {
    @Test
    void concurrentDuplicateGuessPreservesScorePrivacyLateJoinAndLeaveAck() throws Exception {
        try (PrivateFlowCheck fixture = new PrivateFlowCheck()) {
            // Đi qua GameService/roomLock/outbox, nhận packet bằng socket thật.
            fixture.privateGame();
        }
    }

    @Test
    void correctGuessAfterDeadlineCannotAwardPointsBeforeTick() throws Exception {
        try (PrivateFlowCheck fixture = new PrivateFlowCheck()) {
            // Tăng clock giả qua hạn, gửi guess trước khi scheduler kịp tick.
            fixture.expiry();
        }
    }
}

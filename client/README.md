# Module Client — JavaFX và TCP NDJSON

Client dự kiến dùng **Java 21 + JavaFX 21.0.13**, giao tiếp TCP với server bằng NDJSON UTF-8. UI được dựng bằng Java, chưa dùng FXML.

**Trạng thái:** đây là hướng dẫn chuyển đổi. POM/ClientMain hiện có vẫn cần sửa theo [PFX](../../docs/tv2/05a-patch-javafx-setup.md); lần cập nhật này chỉ thay tài liệu, chưa chuyển code client thật.

## Phân công và luồng

- TV2: ConnectView, LobbyView, ChatGuessPanel, PlayerScorePanel, GameOverDialog.
- TV3: SocketClient, reader/writer, ClientController, Stage/Scene, GameView, TopStatusBar, WordChoiceDialog, DrawingCanvas và StrokeThrottler.
- `ClientActions` là callback từ view sang controller. Network I/O chạy nền; DTO nhận được chuyển lên JavaFX Application Thread bằng `Platform.runLater`.
- Server quyết định điểm, đáp án và hết giờ. Client hiển thị snapshot, không tự cộng điểm hoặc quyết định chuyển lượt.

## Thứ tự thực hiện

Đọc [kế hoạch TV2](../../docs/tv2/README.md), thực hiện [PFX](../../docs/tv2/05a-patch-javafx-setup.md), rồi P06/P07/P08. PFX có code Maven và khung Application đầy đủ, P07 có UiPreview chạy không cần socket.

## Build và chạy sau chuyển đổi

Chạy từ **repo root**, với JDK 21 và Maven:

```powershell
mvn -f source/pom.xml -pl common -am install
mvn -f source/client/pom.xml compile javafx:run
```

Khi thay common, install lại common. Mỗi terminal chạy một client. Không thêm `-am` vào lệnh `javafx:run` của client.

Preview UI, sau khi tự tạo class UiPreview theo P07:

```powershell
mvn -f source/client/pom.xml compile javafx:run "-Djavafx.mainClass=com.skribbl.client.UiPreview"
```

Đóng gói từ repo root rồi chạy JAR với SDK JavaFX đúng OS/CPU:

```powershell
mvn -f source/pom.xml package
$javafxLib = 'C:\Tools\javafx-sdk-21.0.13\lib'
java --module-path "$javafxLib" --add-modules javafx.controls -jar source/client/target/skribbl-client.jar
```

Đường dẫn SDK là ví dụ. PFX giữ JavaFX ngoài shaded JAR; không chạy riêng `java -jar` khi chưa cung cấp JavaFX runtime. Native runtime/installer cần đóng gói theo nền tảng.

## Cấu hình và kiểm tra

Mặc định server `127.0.0.1:8888`, nickname 1–20 ký tự; nhập IP LAN nếu server ở máy khác. Khởi động server trước khi thử connect.

Kiểm tra CONNECT_ACK trước khi chuyển lobby, ROOM_UPDATE trước khi coi create/join/start thành công, và snapshot rỗng xác nhận LEAVE_ROOM. Sau disconnect/reconnect phải bỏ packet của phiên cũ, đóng dialog cũ và mở lại ConnectView.

Trong `ClientApplication.stop()`, đóng socket và yêu cầu dừng executor; không chờ mạng lâu trên FX thread. Canvas/GraphicsContext và controls chỉ cập nhật trên FX thread. Nghiệm thu end-to-end theo [P08](../../docs/tv2/08-patch-nghiem-thu.md).

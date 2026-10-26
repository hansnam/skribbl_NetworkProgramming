# BÀI TẬP LỚN: LẬP TRÌNH MẠNG  

## Skribbl Clone – Ứng dụng Trò chơi Vẽ và Đoán chữ Trực tuyến Nhiều người chơi

> 📘 *Mẫu README này là khung hướng dẫn. Sinh viên chỉ cần điền thông tin của nhóm và nội dung dự án theo từng mục.*

---

## 🧑‍💻 THÔNG TIN NHÓM

| STT | Họ và Tên | MSSV | Email | Đóng góp |
|-----|-----------|------|-------|----------|
| 1 | Trần Hán Nam | B23DCCN590 | a@example.com | ... |
| 2 | Đàm Tiến Thành  | B23DCCN764 | b@example.com | ... |
| 3 | Đinh Minh Tuân | B23DCCN880 | c@example.com | ... |

**Tên nhóm:** Nhóm 20 – Lập trình mạng  
**Chủ đề đã đăng ký:** Skribbl clone

---

## 🧠 MÔ TẢ HỆ THỐNG

Hệ thống là ứng dụng trò chơi **vẽ và đoán từ khóa trực tuyến nhiều người chơi (Skribbl Clone)** được xây dựng theo mô hình **Client–Server** dựa trên kết nối **TCP Socket** hai chiều thời gian thực:

- **Server:** Đóng vai trò trung tâm điều phối có thẩm quyền (*Server-Authoritative*), lắng nghe và phục vụ đồng thời nhiều kết nối Client. Server quản lý nhiều phòng chơi độc lập trên bộ nhớ (`RoomManager`), điều khiển máy trạng thái vòng đời ván đấu (`GameEngine`), đồng bộ đồng hồ đếm ngược, mở dần chữ cái gợi ý, kiểm tra đáp án tiếng Anh, tính điểm thưởng theo tốc độ đoán, chuyển tiếp nét vẽ thời gian thực và xử lý an toàn các tình huống ngắt kết nối đột ngột.
- **Client:** Ứng dụng Desktop cung cấp giao diện tương tác trực quan gồm màn hình cấu hình kết nối (`IP/Port/Nickname`), sảnh chờ đa phòng (`Lobby`), bảng vẽ đồng bộ chống răng cưa (`DrawingCanvas`), thanh công cụ chọn màu/độ dày nét vẽ/tẩy/xóa bảng, khung trò chuyện kết hợp đoán từ và bảng xếp hạng điểm số trực tiếp.
- **Cơ chế giao tiếp:** Hai phía trao đổi thông điệp theo giao thức tầng ứng dụng tự thiết kế sử dụng định dạng **NDJSON (Newline-Delimited JSON)** mã hóa `UTF-8` trên luồng TCP (`TCP_NODELAY`), kết hợp tách biệt luồng đọc/ghi mạng với luồng giao diện (*Swing EDT*) để đảm bảo độ mượt và an toàn đa luồng.

**Cấu trúc logic tổng quát:**
```text
+--------------------+                               +----------------------------------+
|  Client 1 (Drawer) | <=== TCP Socket (NDJSON) ===> |                                  |
+--------------------+                               |     Multi-threaded TCP Server    |
+--------------------+                               |  - ClientSession (VirtualThread) |
| Client 2 (Guesser) | <=== TCP Socket (NDJSON) ===> |  - RoomManager (Multi-room)      | <--> [statics/words_en.csv]
+--------------------+                               |  - GameEngine & TurnScheduler    |
+--------------------+                               |  - CanvasSync & ScoreService     |
| Client N (Guesser) | <=== TCP Socket (NDJSON) ===> |                                  |
+--------------------+                               +----------------------------------+
```

**Sơ đồ hệ thống:**

![System Diagram](./statics/diagram.png)

---

## ⚙️ CÔNG NGHỆ SỬ DỤNG

| Thành phần | Công nghệ / Thư viện | Ghi chú & Vai trò trong hệ thống |
|------------|----------------------|----------------------------------|
| **Ngôn ngữ chính** | **Java 21** | Phát triển toàn bộ `common`, `server` và `client`; áp dụng OOP, `record`, đa luồng (`Virtual Threads`, `ScheduledExecutorService`). |
| **Giao tiếp mạng** | **TCP Socket (`java.net`)** | Sử dụng `ServerSocket`, `Socket`, `BufferedReader`, `BufferedWriter` (`UTF-8`, `TCP_NODELAY`) trao đổi dữ liệu song công. |
| **Định dạng bản tin** | **JSON + Jackson Databind** | Chuẩn hóa giao thức **NDJSON** giữa các module, thuận tiện kiểm tra và gỡ lỗi gói tin mạng. |
| **Giao diện (Client)** | **Java Swing + Java2D + FlatLaf** | Xây dựng màn hình Lobby, bảng vẽ Canvas chống răng cưa, khung Chat, đồng hồ đếm ngược và bảng điểm hiện đại. |
| **Lưu trữ dữ liệu** | **In-memory + CSV Dataset** | Quản lý trạng thái các phòng trên RAM (`ConcurrentHashMap`); nạp kho từ vựng tiếng Anh từ `statics/words_en.csv`. |
| **Build & Kiểm thử** | **Maven + JUnit 5 + Git/GitHub** | Tổ chức Maven Multi-module, đóng gói thực thi `.jar`, viết Unit Test và quản lý phiên bản làm việc nhóm. |

---

## 🚀 HƯỚNG DẪN CHẠY DỰ ÁN

### 1. Clone repository
```bash
git clone <repository-url>
cd assignment-network-project
```

### 2. Chạy server
```bash
cd source/server
# Các lệnh để khởi động server
```

### 3. Chạy client
```bash
cd source/client
# Các lệnh để khởi động client
```

### 4. Kiểm thử nhanh
```bash
# Các lệnh test
```

---

## 🔗 GIAO TIẾP (GIAO THỨC SỬ DỤNG)

| Endpoint | Protocol | Method | Input | Output |
|----------|----------|--------|-------|--------|
| `/health` | HTTP/1.1 | GET | — | `{"status": "ok"}` |
| `/compute` | HTTP/1.1 | POST | `{"task":"sum","payload":[1,2,3]}` | `{"result":6}` |

---

## 📊 KẾT QUẢ THỰC NGHIỆM

> Đưa ảnh chụp kết quả hoặc mô tả log chạy thử.

![Demo Result](./statics/result.png)

---

## 🧩 CẤU TRÚC DỰ ÁN
```text
skribbl-app/
├── README.md                          # Tài liệu mô tả tổng quan hệ thống, giao thức và hướng dẫn chạy
├── INSTRUCTION.md                     # Tài liệu hướng dẫn bài tập lớn của giảng viên
├── .gitignore                         # Cấu hình loại trừ file biên dịch và IDE cho Git
├── statics/                           # Chứa hình ảnh minh họa, sơ đồ kiến trúc và bộ từ vựng
│   ├── logo.png
│   ├── diagram.png
│   └── words_en.csv
└── source/                            # Toàn bộ mã nguồn dự án (Maven Multi-module)
    ├── .gitignore
    ├── pom.xml                        # Maven Parent POM quản lý chung các module
    ├── common/                        # Module dùng chung (Giao thức NDJSON, DTOs, Models, Utils)
    │   ├── pom.xml
    │   └── src/main/java/com/skribbl/common/
    │       ├── protocol/              # Định nghĩa MessageType, NetworkMessage, JsonCodec
    │       ├── dto/                   # Các gói dữ liệu trao đổi giữa Client và Server
    │       ├── model/                 # Mô hình dữ liệu người chơi, phòng, nét vẽ
    │       └── util/                  # Xử lý che ký tự gợi ý và khoảng cách chuỗi Levenshtein
    ├── server/                        # Module phía Server
    │   ├── README.md
    │   ├── pom.xml
    │   └── src/main/java/com/skribbl/server/
    │       ├── net/                   # Lắng nghe ServerSocket và quản lý phiên ClientSession
    │       ├── handler/               # Định tuyến và phân phối bản tin từ Client
    │       ├── room/                  # Quản lý đa phòng chơi (RoomManager, GameRoom)
    │       └── game/                  # Máy trạng thái luật chơi, bộ đếm giờ, tính điểm, đồng bộ vẽ
    └── client/                        # Module phía Client
        ├── README.md
        ├── pom.xml
        └── src/main/java/com/skribbl/client/
            ├── net/                   # Kết nối SocketClient và luồng đọc dữ liệu bất đồng bộ
            ├── controller/            # Điều phối sự kiện từ luồng mạng sang giao diện Swing EDT
            └── ui/                    # Giao diện người dùng (lobby/, canvas/, chat/, game/)
```

---

## 🧩 HƯỚNG PHÁT TRIỂN THÊM

> Nêu ý tưởng mở rộng hoặc cải tiến hệ thống.

- [ ] Cải thiện giao diện người dùng
- [ ] Thêm tính năng xác thực và phân quyền
- [ ] Tối ưu hóa hiệu suất
- [ ] Triển khai trên cloud

---

## 📝 GHI CHÚ

- Repo tuân thủ đúng cấu trúc đã hướng dẫn trong `INSTRUCTION.md`.
- Đảm bảo test kỹ trước khi submit.

---

## 📚 TÀI LIỆU THAM KHẢO

> (Nếu có) Liệt kê các tài liệu, API docs, hoặc nguồn tham khảo đã sử dụng.

> (Nếu có) Liệt kê các tài liệu, API docs, hoặc nguồn tham khảo đã sử dụng.
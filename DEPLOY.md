# Demo server TCP trên Railway

Server chạy trên cloud; người chơi vẫn chạy ứng dụng JavaFX trên máy của mình.
Đây là TCP socket, không phải trang web mở bằng trình duyệt.

Railway Trial cấp 5 USD tài nguyên trong tối đa 30 ngày; hết tiền hoặc hết hạn thì
trial kết thúc. Sau đó Free có 1 USD/tháng, không bảo đảm đủ cho server chạy 24/24.
Chỉ dùng Trial/Free để demo, không cần nâng cấp gói trả phí trong hướng dẫn này.
Tài khoản Limited Trial có giới hạn mạng; cần kiểm tra tài khoản được Full Trial
để tránh vướng giới hạn khi mở TCP ra internet.

Nguồn: [Free Trial](https://docs.railway.com/pricing/free-trial),
[TCP Proxy](https://docs.railway.com/networking/tcp-proxy).

## Deploy

1. Commit và push `Dockerfile`, `.dockerignore` cùng code mới lên GitHub.
2. Đăng nhập [Railway](https://railway.com/) bằng GitHub, kiểm tra còn trial credit.
3. Chọn **New Project → Deploy from GitHub repo**, chọn repo và nhánh vừa push.
   Giữ **Root Directory** ở thư mục gốc repo để Railway tự nhận `Dockerfile`.
   Docker chỉ build `common` và `server`, dùng Java 25 theo POM hiện tại.
4. Trong service, giữ **1 replica** vì phòng và trạng thái game đang lưu trong RAM.
   Nếu bật **Serverless**, tắt tùy chọn này khi cần server chờ người chơi liên tục.
   Không cấu hình HTTP healthcheck vì server chỉ nhận TCP.
5. Sau khi build/start thành công, vào **Settings → Networking → TCP Proxy**,
   nhập **8888** là cổng bên trong container.
6. Railway cấp địa chỉ dạng `ten-server.proxy.rlwy.net:15140`.
   Đây chỉ là ví dụ; dùng đúng domain và cổng được cấp cho service của bạn.

Không cần đổi cổng trong `ServerMain` hay thêm biến `PORT`: server nghe ở 8888,
TCP Proxy chuyển tiếp từ cổng công khai vào cổng đó.

## Kết nối từ client

Chạy client như trước. Trong lobby:

- Ô địa chỉ server: nhập domain, ví dụ `ten-server.proxy.rlwy.net`.
- Ô cổng TCP bên cạnh: nhập cổng công khai, ví dụ `15140`.
- Nhập nickname rồi tạo phòng; máy khác dùng cùng domain/cổng và nhập mã phòng để vào.

Không thêm `https://`, không ghép `:15140` vào ô địa chỉ và không dùng cổng 8888
ở client trừ khi Railway thực sự cấp cổng công khai đó.
Chạy local vẫn dùng `127.0.0.1` và `8888` như cũ.

## Kiểm tra trước buổi demo

- Dùng hai client, nên có một máy qua mạng khác/hotspot, cùng vào một phòng.
- Thử bắt đầu game, chọn từ, vẽ, đoán từ và rời phòng.
- Kiểm tra credit còn lại trên Railway. Khi không cần demo, dừng service để tiết kiệm.
- Restart hoặc redeploy sẽ làm mất phòng đang chạy vì trạng thái nằm trong RAM.

Nếu có Docker ở máy local, có thể kiểm tra image trước khi push:

```powershell
docker build -t skribbl-server .
docker run --rm -p 8888:8888 skribbl-server
```

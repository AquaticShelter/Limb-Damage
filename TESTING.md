# Limb Damage — checklist test thủ công

Chuẩn bị: bật `logHits = true` trong `config/limbdamage-common.toml`, có TACZ + Accurate
Hitboxes + 1 khẩu súng chính xác (sniper/DMR dễ nhắm bộ phận nhất). Nhắm vào armor stand không
được — nó không phải LivingEntity humanoid theo tỉ lệ (0.5×1.975 — thực ra LỌT auto-detect,
dùng được để test tĩnh!). Tốt nhất: 1 người chơi thứ 2 hoặc zombie bị trói/nametag đứng yên.

1. **Chân**: bắn vào cẳng/đùi (nửa dưới). Log phải ra `LEGS ... x0.8`, damage giảm đúng 20%.
2. **Thân giữa**: bắn giữa ngực/bụng. Log `BODY ... x1.0`, damage không đổi.
3. **Tay**: bắn mép ngoài thân, ngang tầm ngực (dễ nhất: đứng đối diện, nhắm lệch hẳn sang
   vai/tay). Log `ARMS ... x0.8`. Có Accurate Hitboxes thì bắn cả phần tay VƯƠN RA NGOÀI hitbox
   vanilla (giơ tay/chạy) — vẫn phải ra ARMS (điểm trúng từ AH nằm ngoài box nhưng lateral cao).
4. **Đầu**: bắn đầu. Log `HEAD ... x1.0` — damage giữ nguyên phần TACZ headshot đã nhân sẵn,
   KHÔNG nhân thêm.
5. **Ranh giới TACZ headshot**: bắn đúng tầm cổ (~y +1.4 từ chân), CĂN GIỮA thân (lateral thấp).
   TACZ tính headshot và mod này tính HEAD phải cùng nhau (đối chiếu damage số học với phát bắn
   ngực).
5b. **Vai / tay giơ cao (regression 1.0.2)**: nhắm vào vai hoặc cánh tay đang giơ lên (ví dụ tư thế
   ngắm/reload) ở đúng độ cao ngang đầu/cổ — tức lệch hẳn sang bên NHƯNG cùng tầm y với test 5. Log
   phải ra `ARMS ... x0.8`, KHÔNG phải `HEAD`. Quan trọng hơn: so sánh số damage với 1 phát bắn thân
   giữa (BODY, không headshot) cùng khoảng cách — damage vai phải xấp xỉ 0.8× damage thân, KHÔNG
   được cao bằng hoặc gần bằng damage headshot ở test 5 (nếu vẫn cao ngang headshot nghĩa là cờ
   headshot của TACZ chưa được sửa đúng, chỉ có multiplier x0.8 áp lên trên damage đã bị TACZ cộng
   headshot sai — kiểm tra lại log xem việc rebuild `EntityResult` trong `TaczHitResultMixin` có
   thất bại/warning không).
5c. **Trúng mặt trước bình thường (regression 1.0.3, đã fix ở 1.0.4)**: bắn thẳng vào giữa ngực
   hoặc giữa đầu 1 mob đứng yên đối diện trực tiếp — bất kỳ mob nào, không cần zombie/husk. Log
   phải ra đúng `BODY`/`HEAD` như bình thường, KHÔNG được ra `ARMS`. Đây là case bản 1.0.3 fail
   100% (mọi phát trúng mặt trước đều thành ARMS do lẫn khoảng lệch nền ~0.3 từ việc raycast chỉ
   chạm được mặt ngoài, không chạm được tâm hình học theo chiều sâu). Nếu vẫn ra `ARMS`, xem
   `forward`/ngưỡng trong log — forward > 0.55*width/0.6 mà lẽ ra phát này phải thấp hơn nhiều thì
   `armMinForward` đang để quá thấp so với độ dày hitbox thực tế của mob đó, chỉnh lên trong config.
5d. **Zombie/Husk/Drowned — tay giơ trước mặt (chưa có bằng chứng thực tế, xem thêm nếu nghi ngờ)**:
   đứng đối diện trực tiếp 1 zombie/husk đứng yên, nhắm vào 1 trong 2 tay đang giơ ra trước ở độ
   cao ngang cổ/đầu. Nếu log ra `HEAD` thay vì `ARMS` dù rõ ràng đang trúng tay chứ không phải đầu:
   xem `forward`/ngưỡng trong log, forward lúc đó cần vượt `armMinForward` (mặc định 0.55) mới bắt
   được — nếu forward đo được nằm dưới ngưỡng đó (tay zombie không vươn xa hơn baseline mặt trước
   là bao), hạ `armMinForward` xuống dần (vd 0.4) và test lại 5c song song để đảm bảo không bị
   regression lại — gửi log kèm số liệu để mình tính ngưỡng phù hợp thay vì đoán tiếp.
6. **Cung tên vanilla**: lặp lại 1-4 bằng cung. Nguồn vị trí là ProjectileImpactEvent — vẫn phải
   phân vùng đúng.
7. **Melee**: đánh kiếm vào chân (nhìn xuống chân khi chém) → LEGS x0.8; nhìn đầu → HEAD.
   Tắt `applyToMelee` → melee không đổi damage nữa.
8. **Mob humanoid**: bắn zombie/skeleton vào chân → x0.8. Bắn bò/gà → không có log nào (không
   lọt auto-detect).
9. **Baby zombie**: bắn phần dưới → LEGS (tỉ lệ theo hitbox nhỏ của nó).
10. **Crouch**: mục tiêu crouch, bắn đầu → vẫn HEAD (fraction theo hitbox 1.5 hiện tại).
11. **Bơi/crawl**: mục tiêu bơi ngang, bắn bất kỳ đâu → BODY x1.0 (không đoán bừa khi nằm ngang).
12. **Nổ**: cho nổ TNT/lựu đạn cạnh mục tiêu → damage không đổi, không log (không có vị trí trúng).
13. **Config reload**: đổi `arms = 0.5`, vào lại thế giới, bắn tay → x0.5.
14. **Không có TACZ** (môi trường test riêng): mod load bình thường, không warning mixin; cung
    tên + melee vẫn hoạt động.
15. **Multiplayer**: lặp 1-4 giữa 2 client qua server — damage tính server-side nên kết quả phải
    y hệt singleplayer.
16. **Đỉnh đầu redo trên 1.0.4**: lặp lại đúng test đã báo lỗi trước đó (bắn đỉnh đầu Husk bằng
    TACZ, mob đứng yên không tấn công) với `logHits=true`. Kỳ vọng: `HEAD`. Nếu vẫn ra `BODY`, gửi
    lại dòng log kèm `heightFraction`/`boxY` — đây mới là dữ liệu cần để sửa tiếp, đừng đoán.
17. **Kill message**: `announceKills=true` (mặc định), hạ máu 1 mob/player về gần 0 bằng 1 phát
    trúng rõ ràng (VD chân), rồi kết liễu bằng phát khác (VD đầu) → dòng chat kill-message phải mô
    tả ĐÚNG phát cuối cùng (đầu), không phải phát trước đó (chân) — do `CombatLog` bị ghi đè mỗi
    hit, chỉ giữ lại hit gần nhất khi chết. Tắt `announceKills` → không có dòng chat thêm, damage
    vẫn tính bình thường (dòng ghi CombatLog nằm chung nhánh config này nên tắt luôn cả 2).
18. **Kill message không có attacker rõ ràng**: mob chết vì rơi/lửa sau khi từng bị LimbDamage bắn
    trúng trước đó KHÁ LÂU (>30s) → không có dòng kill-message nào cả (entry đã bị sweep dọn),
    tránh gán nhầm cái chết cho 1 phát bắn không liên quan từ rất lâu trước.
19. **OBB classification bật (mặc định, 1.1.1+)**: bắn TACZ vào Husk/player, `logHits=true`, xem
    log có `[source=AH-OBB]` thay vì dãy `heightFraction=...` như trước không — nếu KHÔNG thấy
    `[source=AH-OBB]` bao giờ dù chắc chắn có AH cài, kiểm tra: (a) bản AH đang chạy có đúng file id
    Curse Maven đã build không (API lệch sẽ fail âm thầm về `null`, không crash), (b)
    `useObbClassification` có đang `true` không, (c) entity đó AH có dữ liệu bone hay không (dùng
    F3+B của AH xem có vẽ box xanh quanh nó không — không có nghĩa AH cũng không có gì để mình
    dùng, sẽ tự rớt về heuristic, không phải bug).
20. **Đỉnh đầu / vai, lại — trên nhánh OBB**: lặp lại đúng test đã báo lỗi trước đó (bắn đỉnh đầu,
    rồi bắn vai/tay giơ cao, Husk đứng yên) — log phải thấy `[source=AH-OBB]` và PART đúng
    (HEAD/ARMS tương ứng) mà KHÔNG cần đụng tới `headBottomFraction`/`armMinLateral`/`armMinForward`
    gì cả, vì nhánh này không dùng các config đó. Nếu vẫn sai ở đây, đây mới thật sự là bug trong
    `ObbBodyPartClassifier` (hoặc ngưỡng `obbArmOffset`/`obbHeadYTolerance`/`obbLegHeightFraction`
    cần tinh chỉnh) — gửi log kèm mô tả bạn nhắm chỗ nào.
21. **Tắt OBB để so sánh**: `useObbClassification = false`, lặp lại test 20 — phải quay về đúng
    hành vi heuristic cũ (có `heightFraction=...` trong log, không có `[source=AH-OBB]`) — xác nhận
    toggle hoạt động, hữu ích để tách biến khi không chắc lỗi nằm ở nhánh nào.

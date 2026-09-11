# Limb Damage — Forge 1.20.1

Locational damage kiểu COD: **tay và chân nhận sát thương ít hơn 20%** (mặc định), multiplier
config được riêng cho từng vùng cơ thể — áp dụng cho **players và mobs có hitbox tương đồng
players** (zombie, skeleton, villager, pillager, piglin... tự nhận diện qua tỉ lệ hitbox).

Cài **cả client lẫn server** (logic tính damage chạy server-side qua `LivingHurtEvent`; bản client
chỉ có tác dụng cho singleplayer/LAN).

## Cách hoạt động — 4 vùng, phân loại theo VỊ TRÍ viên đạn trúng

```
        +--+----+--+  1.0
        |A | HE |A |       sideways <= 0.24 VÀ forward <= 0.55 VÀ >= 0.78 chiều cao hitbox => HEAD
        |R | AD |R |       sideways > 0.24 HOẶC forward > 0.55 (bất kỳ chiều cao nào, trên 0.42) => ARMS
        +--+----+--+  0.78
        |A | BO |A |
        |R | DY |R |
        +--+----+--+  0.42
        |    LEGS   |       sideways <= 0.24 VÀ forward <= 0.55 VÀ <= 0.42 chiều cao hitbox => LEGS
        +-----------+  0.0
```

- **Tách riêng 2 trục theo hướng thân (`yBodyRot`)**: `sideways` (trái/phải, ngưỡng chặt 0.24 —
  đúng nguyên bản 1.0.2, đã kiểm chứng bằng log thật) và `forward` (trước/sau, ngưỡng LỎNG hơn
  nhiều — mặc định 0.55). Bản 1.0.3 từng gộp 2 trục làm 1 (đo khoảng cách toàn phương, không quan
  tâm hướng) để bắt cả tay zombie giơ ra trước — nhưng log thật cho thấy cách đó SAI: raycast chỉ
  có thể chạm vào MẶT NGOÀI hướng về người bắn, không bao giờ chạm đúng tâm hình học theo chiều
  sâu, nên MỌI phát trúng mặt trước (kể cả trúng thẳng giữa đầu) đều có sẵn 1 khoảng lệch nền
  ~0.3 (nửa độ dày hitbox) — vượt luôn ngưỡng 0.24 dùng chung, khiến session test thật ra **toàn
  bộ ~20 phát đều bị tính ARMS** bất kể ngắm chỗ nào. 1.0.4 quay lại `sideways` chặt (không bị
  nhiễu bởi hướng bắn) và thêm `forward` lỏng hơn hẳn baseline ~0.3 đó, chỉ bắt tay THỰC SỰ vươn
  xa hẳn ra trước mặt chứ không phải mọi cú trúng mặt trước bình thường.
- Tất cả tính theo **tỉ lệ hitbox hiện tại** — nên crouch (hitbox thấp hơn), baby zombie (hitbox
  nhỏ), entity bị scale (Pehkui...) đều tự đúng theo, không cần config riêng.
- Tư thế nằm ngang (bơi/crawl/elytra/ngủ): phân vùng theo chiều cao vô nghĩa → tính là BODY (x1.0
  mặc định, tức không đổi gì) thay vì đoán bừa.
- `logHits=true` log kèm `heightFraction`, `boxY`, `sideways`/ngưỡng, `forward`/ngưỡng — nếu
  1 hit nào đó phân loại sai, số liệu trong log là đủ để soi ra nguyên nhân mà không cần đoán.

## Nguồn vị trí trúng đạn — 3 tầng ưu tiên

1. **CHÍNH XÁC**: `ProjectileImpactEvent` cho mọi projectile vanilla-pipeline (tên, đinh ba,
   projectile mod thường), và **mixin vào `EntityUtil#getHitResult` của TACZ** cho đạn TACZ (đạn
   TACZ KHÔNG đi qua pipeline vanilla — không có event nào khác mang vị trí trúng cả). Khi có
   **Accurate Hitboxes**, cả 2 nguồn này đều nhận thẳng điểm giao cắt với MODEL thật từ raycast
   của AH (kể cả trúng tay nằm NGOÀI hitbox vanilla) — không cần code tích hợp AH nào thêm, độ
   chính xác tự chảy qua.

   Riêng nhánh TACZ (từ 1.1.1), nếu AH có sẵn dữ liệu OBB theo từng khúc xương cho đúng phát này,
   mixin còn tự raycast lại vào TỪNG OBB đó và phân loại PART thẳng từ hình dạng/vị trí khúc xương
   thật (`ObbBodyPartClassifier`) — xem mục riêng bên dưới — thay vì dựng lại xấp xỉ từ 1 điểm
   trúng phẳng như phần "Cách hoạt động" ở trên. Khi có kết quả OBB, nó thắng tuyệt đối, bỏ qua
   toàn bộ heuristic height/sideways/forward cho phát đó.
2. **CLIP QUỸ ĐẠO**: projectile lạ không có event → cắt đoạn bay hiện tại của nó với hitbox mục
   tiêu, lấy điểm vào. Đạn bay gần như phẳng nên phân biệt head/legs vẫn chuẩn, chỉ ranh giới
   arm/body kém chính xác hơn chút ở mép hitbox.
3. **TIA NGẮM (melee, tắt được)**: ray từ mắt người đánh theo hướng nhìn, cắt với hitbox mục tiêu
   — nhìn vào đâu thì chém trúng đó, giả định chuẩn của mọi game locational-melee.

Damage **không có vị trí** (nổ, lửa, độc, rơi, /kill...) → không đổi gì, đúng bản chất.

## Phân loại theo khúc xương thật (Accurate Hitboxes OBB) — từ 1.1.1

Nguồn gốc: `com.szo9k4.taczahbcompat` — 1 prototype riêng (cùng tác giả) chỉ giải quyết headshot
boolean cho player, dùng ý tưởng: khúc xương ĐẦU gần như HÌNH LẬP PHƯƠNG (rộng ≈ cao ≈ sâu), và
trên bộ xương humanoid bình thường nó cũng là khúc xương lập-phương CAO NHẤT — 2 đặc điểm không
khúc xương nào khác có, nên không cần tinh chỉnh ngưỡng height/lateral gì để tìm ra nó.

Đã sửa 2 chỗ API lệch so với AH 2.3.1 (prototype build trước đó, "outdated" như đã cảnh báo):
- Constructor `OrientedBoundingBox` giờ có thêm tham số `offset` (3 tham số, không phải 2) —
  gọi kiểu cũ không compile được với 2.3.1.
- `BlueprintManager.serverBlueprints` key bằng **String** từ `BlueprintPose.getCacheKey(entity)`,
  không phải `ResourceLocation` thô — bản cũ tra nhầm kiểu key nên tầng blueprint (fallback cuối)
  luôn trả `null` một cách âm thầm (compile được, chỉ là không bao giờ khớp).

Đối chiếu cả 2 chỗ trên với chính mixin TACZ compat CỦA AH (`TaczEntityUtilMixin`, decompile trực
tiếp từ jar 2.3.1) để xác nhận đúng thứ tự 3 tầng tra cứu dữ liệu xương (bridge trực tiếp → dynamic
theo người bắn → static blueprint) và cách dùng constructor/cache key chuẩn.

Mở rộng so với prototype gốc:
- Không chỉ có headshot boolean — phân loại đủ 4 vùng: LEGS theo vị trí khúc xương trong TOÀN BỘ
  khoảng chiều cao của chính bộ xương đó (không phải hitbox ngoài), ARMS/BODY theo khoảng cách
  NGANG từ tâm khúc xương trúng tới tâm khúc xương đầu — tâm-tới-tâm thật từ transform xương, nên
  không dính khoảng lệch nền "trúng mặt nào" như trục `forward` ở heuristic phía trên (đó là điểm
  raycast bị clip vào bề mặt, còn đây là tâm hình học thật).
- Không giới hạn chỉ player — hoạt động với mọi entity có dữ liệu xương (Husk, mob khác...), vì
  việc AH giới hạn player trong prototype gốc có vẻ do phạm vi hẹp của nó (chỉ cần headshot PvP)
  chứ không phải giới hạn kỹ thuật.

Chưa được kiểm chứng bằng log thật (ngưỡng `obbArmOffset` đặc biệt là số đoán, xem comment trong
`LimbConfig`) — tắt bằng `useObbClassification = false` nếu có vấn đề, mọi thứ quay về heuristic
cũ y hệt trước 1.1.1. Bật `logHits=true`, log sẽ ghi rõ `[source=AH-OBB]` cho phát nào dùng nhánh
này thay vì heuristic, để phân biệt khi debug.

## Không xung đột TACZ headshot

TACZ tự nhân damage headshot TRƯỚC khi damage tới mod này — vì vậy vùng HEAD mặc định để **x1.0**
(không đổi), tránh nhân chồng 2 lần. Ranh giới HEAD (0.78 × 1.8 = y1.404) cũng được canh trùng với
vạch headshot mà Accurate Hitboxes tính cho TACZ (eye height ±0.25 = y1.37..1.87).

**Vấn đề**: AH tính cờ headshot đó CHỈ theo chiều cao (điểm trúng có nằm trong dải ±0.25 quanh mắt
hay không), hoàn toàn không xét lệch ngang — nên 1 phát trúng vai/cánh tay giơ cao vẫn bị AH đóng
dấu "headshot" nếu nó rơi vào đúng dải chiều cao đó, và TACZ cộng damage headshot dựa trên cờ đó
TRƯỚC KHI `LivingHurtEvent` bắn ra. Đến lúc mod này thấy damage thì bonus headshot sai đã cộng vào
rồi — nhân thêm x0.8 (arms) lên trên chỉ giảm bớt chứ không loại bỏ được phần cộng sai đó.

**Cách fix (từ 1.0.2)**: `TaczHitResultMixin` đã chạy SAU AH ở đúng điểm inject
(`EntityUtil#getHitResult`, `@At("RETURN")`, priority 2000 > 1000 của AH) để đọc `entity`/`hitVec`
— giờ nó tận dụng luôn vị trí đó để tính lại đúng bằng `BodyPartClassifier` (có xét khoảng cách
ngang, không chỉ chiều cao), rồi build lại `EntityResult` mới với cờ headshot đã sửa TRƯỚC khi
TACZ kịp đọc, thay vì để mod này chỉ chữa cháy ở tầng damage. Chỉ sửa khi mục tiêu đứng thẳng/
crouch (những tư thế `BodyPartClassifier` có đủ dữ kiện để phân loại chắc chắn) — bơi/crawl/ngủ
thì giữ nguyên phán đoán gốc của AH.

Muốn buff/nerf headshot thêm thì chỉnh `head` trong config, nhưng nhớ nó nhân CHỒNG lên multiplier
của TACZ.

## Giới hạn đã biết

Update: phát hiện ban đầu "đỉnh đầu tính thành BODY" hoá ra không phải do animation — log thật
(kèm số liệu `heightFraction`/`horizontalOffset` được thêm ở 1.0.3) cho thấy đó là hệ quả của bug
1.0.3 (mọi phát trúng mặt trước đều bị tính ARMS, xem mục "Cách hoạt động" ở trên) — đã fix ở
1.0.4. Ghi chú animation dưới đây vẫn giữ lại vì về lý thuyết vẫn có thể xảy ra, chỉ là chưa có
bằng chứng thực tế nào cho thấy nó là nguyên nhân thật:

Phân loại dựa trên `getBoundingBox()` **tĩnh** (hình hộp cố định theo pose STANDING/CROUCHING),
không biết gì về animation. Lúc mob đang giữa động tác tấn công (vung tay, cúi người vào đòn đánh
— zombie/husk nghiêng cả người về phía trước khi đấm), mô hình hiển thị lệch khỏi hộp tĩnh đó
trong khoảnh khắc, nên 1 phát trúng ĐÚNG đỉnh đầu về mặt hình ảnh có thể cho `heightFraction` thấp
hơn thực tế lúc đó (dính band BODY) — chưa có cách sửa mà không cần dữ liệu animation runtime từ
AH (hiện AH không expose thứ đó ra ngoài cho mod khác dùng). Nếu gặp lại trường hợp đầu bị tính
BODY sau khi lên 1.0.4: thử lại lúc mob đứng yên (không tấn công) để loại trừ nguyên nhân này; nếu
vẫn sai lúc đứng yên, gửi log kèm `heightFraction`/`boxY` để chẩn đoán tiếp.

## Config (`config/limbdamage-common.toml`)

```toml
[multipliers]
    head = 1.0    # nhân CHỒNG lên headshot multiplier của TACZ nếu > 1.0
    body = 1.0
    arms = 0.8    # tay: -20% mặc định
    legs = 0.8    # chân: -20% mặc định

[entities]
    affectPlayers = true
    affectMobs = true
    autoDetectHumanoid = true          # hitbox 0.45-0.75 rộng, 1.5-2.2 cao => humanoid
    extraEntityIds = []                # ép thêm: ["minecraft:iron_golem", "mod:soldier"]
    blacklistEntityIds = []            # loại trừ, thắng mọi rule khác

[geometry]
    legsTopFraction = 0.42
    headBottomFraction = 0.78
    armMinLateral = 0.24    # trục trái/phải (ngưỡng chặt)
    armMinForward = 0.55    # trục trước/sau (ngưỡng lỏng — xem "Cách hoạt động")

[obbClassification]
    useObbClassification = true   # ưu tiên phân loại từ xương thật AH khi có, xem mục riêng
    obbHeadYTolerance = 0.35
    obbLegHeightFraction = 0.35
    obbArmOffset = 0.2            # chưa kiểm chứng bằng log thật, xem mục riêng

[damageKinds]
    applyToMelee = true

[debug]
    logHits = false       # bật khi test: log PART + vị trí + damage trước/sau cho MỌI hit
    announceKills = true  # broadcast thêm 1 dòng chat khi chết: part/attacker/weapon/dmg
```

## Combat log / kill message

`announceKills = true` (mặc định bật) làm 2 việc:
1. Mỗi hit được phân loại (mọi tier, kể cả melee) được ghi vào `CombatLog` — kèm attacker (lấy từ
   `DamageSource#getEntity()`, đúng cho cả melee lẫn TACZ vì TACZ tự gắn shooter làm true attacker
   của DamageSource) và weapon (item đang cầm tay chính của attacker LÚC ĐÓ — dùng main-hand item
   thay vì đào API riêng của TACZ/Forge để không phụ thuộc version nào cả).
2. Lúc entity chết (`LivingDeathEvent`), lấy hit cuối cùng đã ghi (destructive read — chỉ đọc 1
   lần) và broadcast thêm 1 dòng, VD:
   `[LimbDamage] Steve died — head hit from Alex (Modern Warfare Rifle) for 22.8 dmg (x1.00)`

   Đây là dòng THÊM VÀO bên cạnh death message gốc của vanilla, không thay thế — mod này không hề
   mixin vào `Player#die`/`CombatTracker`, nên không có rủi ro đụng độ với mod khác cũng custom
   death message. Muốn đổi format thì sửa trực tiếp `DamageHandler.onLivingDeath` (đang là
   `StringBuilder` đơn giản, không cần config hoá cho từng phần câu).

   Không kể chuyện chết: nếu entity bị hit nhưng không chết (đa số trường hợp), entry chỉ nằm chờ
   bị hit tiếp ghi đè, và tự dọn sau 30s (`CombatLog.sweep`, chạy chung tick handler với
   `HitLocationTracker`) nếu không có hit nào khác — không tích luỹ vô hạn trên server chạy lâu.

## Kiến trúc / phụ thuộc compile-time

- **Không cần jar TACZ để build** (vẫn như cũ): mixin TACZ target class bằng CHUỖI TÊN
  (`@Mixin(targets = "com.tacz.guns.util.EntityUtil")` + `@Pseudo`), đọc `EntityResult` bằng
  reflection (cached MethodHandle, thử getter `getEntity`/`getHitVec` rồi fallback field
  `entity`/`hitVec`). Để sửa cờ headshot, mod này còn dò thêm constructor 3 tham số
  `(entity, hitVec, headshot)` — cùng constructor mà chính mixin của AH gọi — bằng cách so khớp
  kiểu tham số runtime (`isInstance`) thay vì hardcode class, rồi gọi lại nó qua
  `MethodHandle#invokeWithArguments` để tạo `EntityResult` mới với cờ đã sửa.
- **Từ 1.1.1, CẦN jar Accurate Hitboxes lúc compile** (`compileOnly`, xem `build.gradle`) —
  `ObbBodyPartClassifier` gọi thẳng type của AH (`OrientedBoundingBox`, `IAccurateEntity`...),
  khác hẳn cách tiếp cận reflection-only ở trên. Đổi lại: mọi chỗ đụng tới type của AH đều nằm
  sau `LimbCompat.ACCURATE_HITBOXES_LOADED` (check qua `ModList`, không đụng class nào của AH) —
  nên vẫn chạy tốt khi AH KHÔNG cài lúc runtime (class Java load lười, `ObbBodyPartClassifier`
  không bao giờ được load nếu check đó `false`, nên không có `NoClassDefFoundError`). Không cài
  AH lúc build → lỗi compile ngay (khác với TACZ) — đây là đánh đổi có chủ đích để đổi lấy code
  type-safe hơn hẳn reflection cho phần OBB, xem lý do trong doc của `ObbBodyPartClassifier`.
- Mixin priority **2000** (AH mặc định 1000): AH inject HEAD + cancel vào đúng method này; lệnh
  return do cancel sinh ra là opcode RETURN thật trong bytecode, mixin apply SAU sẽ wrap được cả
  opcode đó — nên tầng capture này thấy đủ cả 2 đường: kết quả model-chính-xác của AH lẫn kết quả
  gốc của TACZ khi không có AH.
- Không có TACZ: mixin plugin (`LimbDamageMixinPlugin`) bỏ qua mixin hoàn toàn, mod vẫn chạy đủ
  cho vanilla projectile + melee.
- TACZ đổi cấu trúc EntityResult trong tương lai: resolve fail 1 lần, log 1 dòng warning, tự
  xuống tầng 2 (clip quỹ đạo) — không bao giờ crash.

## Build

```bash
./gradlew build
```

Không cần sửa gì trước — jar Accurate Hitboxes (`accuratehitboxes-neoforge-2_3_1-1_20_1.jar`, đúng
bản bạn đang chạy) đã nằm sẵn trong `libs/`, `build.gradle` tự nhặt qua wildcard
`accuratehitboxes-*.jar` (không dùng Curse Maven nữa — không phụ thuộc mạng lúc build, không phải
đoán file id). Sau này lên đời AH: xoá jar cũ trong `libs/`, bỏ jar mới vào, build lại — không cần
sửa `build.gradle`. Nếu quên bỏ jar vào (hoặc để lẫn 2 jar cùng lúc), Gradle báo lỗi rõ ràng ngay
từ bước configure, không phải một đống lỗi "cannot find symbol" khó hiểu.

Output: `build/libs/limbdamage-1.20.1-1.1.1.jar`

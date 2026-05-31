"""
AutoDrive — Advisory ADAS (กล้องเดียว / monocular)
====================================================
อ่านวิดีโอ dashcam -> ตรวจจับวัตถุด้วย YOLOv8 -> ประมาณระยะรถคันหน้า
-> วาด overlay (กล่อง + ระยะ + คำสั่ง) แบบเดียวกับ mockup HTML
*** แสดงคำแนะนำบนจอเท่านั้น ไม่ได้ควบคุมรถจริง ***

วิธีใช้ (PowerShell):
    pip install -r requirements.txt

    # 1) ใช้วิดีโอตัวอย่าง (โหลดอัตโนมัติครั้งแรก)
    python autodrive.py

    # 2) ใช้วิดีโอของคุณเอง
    python autodrive.py --source "C:\\path\\to\\your.mp4"

    # 3) ใช้เว็บแคม/กล้องจริง
    python autodrive.py --source 0

    # 4) เซฟผลลัพธ์เป็นไฟล์ (ไม่เปิดหน้าต่าง)
    python autodrive.py --save output.mp4 --no-show

กด  Q  เพื่อออก  /  SPACE  เพื่อหยุดชั่วคราว
"""
import argparse
import os
import sys
import math
import time
import urllib.request
from collections import deque

import cv2
import numpy as np

# ---------------------------------------------------------------------------
# ค่าคงที่ / การตั้งค่า
# ---------------------------------------------------------------------------

# COCO class id ที่เราสนใจ -> (ชื่อแสดง, ความสูงจริงโดยประมาณเป็นเมตร)
CLASSES = {
    0:  ("person",  1.70),
    1:  ("bicycle", 1.10),
    2:  ("car",     1.50),
    3:  ("motorcycle", 1.10),
    5:  ("bus",     3.20),
    7:  ("truck",   3.50),
    9:  ("light",   0.80),   # traffic light
    11: ("sign",    0.75),   # stop sign
}
VEHICLE_IDS = {2, 3, 5, 7}
VULNERABLE_IDS = {0, 1}      # คน / จักรยาน -> อันตรายสูง

# เกณฑ์ระยะ (เมตร) สำหรับตรรกะคำสั่ง
SAFE_DIST = 18.0     # ไกลกว่านี้ = ปลอดภัย
WARN_DIST = 12.0     # ใกล้ = เตือนชะลอ
CRIT_DIST = 7.0      # ใกล้มาก = เบรก
PED_CRIT  = 15.0     # คนในเลน = เบรกฉุกเฉิน
TTC_WARN  = 2.5      # เวลาชน (วินาที)
TTC_CRIT  = 1.2

# สีแบบ BGR (ให้เข้ากับ mockup)
C_DET    = (24, 122, 255)    # ส้ม - ปกติ
C_WARN   = (0, 204, 255)     # เหลือง
C_CRIT   = (85, 45, 255)     # แดง
C_HUD    = (255, 240, 0)     # ฟ้า cyan
C_OK     = (136, 255, 34)    # เขียว
C_BG     = (20, 12, 6)
C_WHITE  = (235, 251, 232)

SAMPLE_URLS = [
    # คลิป dashcam ขับในเมือง จาก Pexels (royalty-free)
    "https://videos.pexels.com/video-files/4644521/4644521-sd_960_540_30fps.mp4",
    "https://videos.pexels.com/video-files/4644521/4644521-uhd_2562_1440_30fps.mp4",
]
SAMPLE_PATH = "sample_dashcam.mp4"
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")


# ---------------------------------------------------------------------------
# ดาวน์โหลดวิดีโอตัวอย่าง
# ---------------------------------------------------------------------------
def download_sample(dst=SAMPLE_PATH):
    if os.path.exists(dst) and os.path.getsize(dst) > 100_000:
        print(f"[ok] มีวิดีโอตัวอย่างแล้ว: {dst}")
        return dst
    opener = urllib.request.build_opener()
    opener.addheaders = [("User-Agent", _UA), ("Referer", "https://www.pexels.com/")]
    urllib.request.install_opener(opener)
    for url in SAMPLE_URLS:
        try:
            print(f"[..] กำลังโหลดวิดีโอตัวอย่างจาก:\n     {url}")

            def _hook(b, bs, total):
                if total > 0:
                    pct = min(100, b * bs * 100 // total)
                    print(f"\r     {pct:3d}%  ({b*bs//1_000_000} MB)", end="")

            urllib.request.urlretrieve(url, dst, _hook)
            print(f"\n[ok] โหลดเสร็จ -> {dst}")
            return dst
        except Exception as e:
            print(f"\n[!] โหลดไม่สำเร็จ ({e}) ลองลิงก์ถัดไป...")
    print("[x] โหลดวิดีโอตัวอย่างไม่ได้ — กรุณาระบุ --source ไฟล์ของคุณเอง")
    print("    หรือโหลดเองฟรีจาก https://www.pexels.com/search/videos/dash%20cam/")
    sys.exit(1)


# ---------------------------------------------------------------------------
# การประมาณระยะ (monocular, pinhole camera)
#   distance ≈ (focal_px * real_height_m) / bbox_pixel_height
# focal_px ประมาณจากความกว้างภาพ + มุมมองแนวนอน (HFOV ~ 60°)
# ---------------------------------------------------------------------------
def focal_from_width(img_w, hfov_deg=60.0):
    return (img_w / 2.0) / math.tan(math.radians(hfov_deg / 2.0))


def estimate_distance(box_h_px, real_h_m, focal_px):
    if box_h_px <= 1:
        return 999.0
    return (focal_px * real_h_m) / box_h_px


# ---------------------------------------------------------------------------
# ตรวจว่าไฟจราจรเป็นสีแดงไหม (เช็คสัดส่วนสีในกล่อง)
# ---------------------------------------------------------------------------
def is_red_light(frame, box):
    x1, y1, x2, y2 = [int(v) for v in box]
    x1, y1 = max(0, x1), max(0, y1)
    crop = frame[y1:y2, x1:x2]
    if crop.size == 0:
        return False
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    red1 = cv2.inRange(hsv, (0, 90, 90), (10, 255, 255))
    red2 = cv2.inRange(hsv, (170, 90, 90), (180, 255, 255))
    red = cv2.countNonZero(red1) + cv2.countNonZero(red2)
    return red > 0.05 * crop.shape[0] * crop.shape[1]


# ---------------------------------------------------------------------------
# วาด overlay
# ---------------------------------------------------------------------------
def draw_panel(img, x, y, w, h, alpha=0.55):
    sub = img[y:y + h, x:x + w].copy()
    cv2.rectangle(sub, (0, 0), (w, h), C_BG, -1)
    img[y:y + h, x:x + w] = cv2.addWeighted(sub, alpha, img[y:y + h, x:x + w], 1 - alpha, 0)


def put_text(img, text, org, scale=0.5, color=C_WHITE, thick=1, bg=False):
    if bg:
        (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, thick)
        x, y = org
        cv2.rectangle(img, (x - 2, y - th - 4), (x + tw + 2, y + 3), C_BG, -1)
    cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thick, cv2.LINE_AA)


def draw_box(img, box, label, dist, color):
    x1, y1, x2, y2 = [int(v) for v in box]
    cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
    # ป้ายคลาส + confidence (บนซ้าย)
    (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
    cv2.rectangle(img, (x1, y1 - th - 6), (x1 + tw + 6, y1), color, -1)
    cv2.putText(img, label, (x1 + 3, y1 - 4), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (10, 10, 10), 1, cv2.LINE_AA)
    # ป้ายระยะ (ขวาล่าง) — ฟ้า เหมือน mockup
    if dist is not None:
        dt = f"{dist:4.1f}m"
        (dw, dh), _ = cv2.getTextSize(dt, cv2.FONT_HERSHEY_SIMPLEX, 0.45, 1)
        cv2.rectangle(img, (x2 - dw - 6, y2), (x2, y2 + dh + 6), C_BG, -1)
        cv2.rectangle(img, (x2 - dw - 6, y2), (x2, y2 + dh + 6), C_HUD, 1)
        cv2.putText(img, dt, (x2 - dw - 3, y2 + dh + 2), cv2.FONT_HERSHEY_SIMPLEX, 0.45, C_HUD, 1, cv2.LINE_AA)


def draw_command(img, W, ico_color, line1, line2):
    """แถบคำสั่งกลางบน"""
    bw, bh = 430, 58
    bx = (W - bw) // 2
    by = 16
    draw_panel(img, bx, by, bw, bh, 0.7)
    cv2.rectangle(img, (bx, by), (bx + bw, by + bh), ico_color, 2)
    cv2.circle(img, (bx + 28, by + 29), 13, ico_color, -1)
    put_text(img, line1, (bx + 56, by + 26), 0.62, C_WHITE, 2)
    put_text(img, line2, (bx + 56, by + 47), 0.42, C_HUD, 1)


def draw_fcw(img, W, H, dist, ttc, color):
    """แถบระยะรถคันหน้า + TTC กลางล่าง"""
    bw, bh = 260, 56
    bx = (W - bw) // 2
    by = H - bh - 14
    draw_panel(img, bx, by, bw, bh, 0.7)
    cv2.rectangle(img, (bx, by), (bx + bw, by + bh), color, 2)
    put_text(img, "LEAD DIST", (bx + 12, by + 18), 0.4, C_WHITE, 1)
    dstr = f"{dist:.0f} m" if dist < 900 else "-- m"
    put_text(img, dstr, (bx + 12, by + 46), 0.8, color, 2)
    put_text(img, "TTC", (bx + 150, by + 18), 0.4, C_WHITE, 1)
    tstr = f"{ttc:.1f} s" if ttc and ttc < 60 else "--"
    put_text(img, tstr, (bx + 150, by + 46), 0.7, color, 2)


def draw_telemetry(img, H, speed_kmh, gas, brk):
    bx, by = 14, H - 70
    draw_panel(img, bx, by, 150, 56, 0.7)
    cv2.rectangle(img, (bx, by), (bx + 150, by + 56), C_HUD, 1)
    put_text(img, f"{speed_kmh:>3.0f}", (bx + 8, by + 38), 1.0, C_HUD, 2)
    put_text(img, "KM/H", (bx + 95, by + 22), 0.4, C_WHITE, 1)
    # แถบเบรก/คันเร่ง
    cv2.rectangle(img, (bx + 95, by + 30), (bx + 142, by + 36), (40, 32, 19), -1)
    cv2.rectangle(img, (bx + 95, by + 30), (bx + 95 + int(47 * gas), by + 36), C_OK, -1)
    cv2.rectangle(img, (bx + 95, by + 42), (bx + 142, by + 48), (40, 32, 19), -1)
    cv2.rectangle(img, (bx + 95, by + 42), (bx + 95 + int(47 * brk), by + 48), C_CRIT, -1)


def draw_datapanel(img, W, fps, frame_i, total, objs):
    bw, bh = 188, 30 + 14 * min(len(objs), 6) + 38
    bx = W - bw - 14
    by = 14
    draw_panel(img, bx, by, bw, bh, 0.62)
    cv2.rectangle(img, (bx, by), (bx + bw, by + bh), C_HUD, 1)
    put_text(img, "CAMERA_01", (bx + 8, by + 18), 0.42, C_HUD, 1)
    put_text(img, "ACTIVE", (bx + bw - 52, by + 18), 0.42, C_OK, 1)
    cv2.line(img, (bx + 6, by + 24), (bx + bw - 6, by + 24), C_HUD, 1)
    put_text(img, f"procFPS {fps:4.1f}", (bx + 8, by + 38), 0.38, C_WHITE, 1)
    put_text(img, f"frame {frame_i}/{total}", (bx + 8, by + 52), 0.38, C_WHITE, 1)
    y = by + 70
    for (name, dist, col) in objs[:6]:
        put_text(img, name, (bx + 8, y), 0.38, col, 1)
        put_text(img, f"d:{dist:.0f}m", (bx + bw - 56, y), 0.38, C_HUD, 1)
        y += 14


# ---------------------------------------------------------------------------
# ตรรกะตัดสินใจ -> คืน (คำสั่ง, คำอธิบาย, สี, gas, brk)
# ---------------------------------------------------------------------------
def decide(lead_dist, ttc, ped_in_lane, red_light):
    # หมายเหตุ: OpenCV วาดฟอนต์ไทยไม่ได้ จึงใช้อังกฤษบนวิดีโอ
    if ped_in_lane:
        return "BRAKE! PEDESTRIAN", "EMERGENCY STOP", C_CRIT, 0.0, 1.0
    if red_light:
        return "RED LIGHT - STOP", "RED LIGHT AHEAD", C_CRIT, 0.0, 0.9
    if lead_dist < CRIT_DIST or (ttc and ttc < TTC_CRIT):
        return "BRAKE! CAR TOO CLOSE", "FORWARD COLLISION", C_CRIT, 0.0, 0.85
    if lead_dist < WARN_DIST or (ttc and ttc < TTC_WARN):
        return "SLOW DOWN - KEEP GAP", "SLOW DOWN", C_WARN, 0.1, 0.4
    return "CRUISING - ROAD CLEAR", "CRUISING", C_OK, 0.5, 0.0


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="AutoDrive advisory ADAS (monocular)")
    ap.add_argument("--source", default=None, help="ไฟล์วิดีโอ, หรือเลขกล้อง (0). ว่าง = โหลดตัวอย่าง")
    ap.add_argument("--model", default="yolov8n.pt", help="โมเดล YOLO (n=เล็ก/เร็ว)")
    ap.add_argument("--conf", type=float, default=0.35, help="confidence ขั้นต่ำ")
    ap.add_argument("--imgsz", type=int, default=640, help="ขนาด inference")
    ap.add_argument("--max-width", type=int, default=1100, help="ย่อความกว้างจอแสดงผล")
    ap.add_argument("--hfov", type=float, default=60.0, help="มุมมองกล้องแนวนอน (องศา) สำหรับคำนวณระยะ")
    ap.add_argument("--save", default=None, help="เซฟผลลัพธ์เป็นไฟล์ mp4")
    ap.add_argument("--no-show", action="store_true", help="ไม่เปิดหน้าต่าง")
    ap.add_argument("--max-frames", type=int, default=0, help="หยุดหลัง N เฟรม (0=ไม่จำกัด) สำหรับทดสอบ")
    ap.add_argument("--skip", type=int, default=0, help="ข้าม N เฟรมต่อ 1 เฟรมที่ประมวลผล (เพิ่ม FPS บน CPU)")
    args = ap.parse_args()

    try:
        from ultralytics import YOLO
    except ImportError:
        print("[x] ยังไม่ได้ติดตั้ง ultralytics — รัน:  pip install -r requirements.txt")
        sys.exit(1)

    # แหล่งวิดีโอ
    src = args.source
    if src is None:
        src = download_sample()
    elif src.isdigit():
        src = int(src)

    print(f"[..] โหลดโมเดล {args.model} (ครั้งแรกจะดาวน์โหลดอัตโนมัติ)")
    model = YOLO(args.model)

    # กล้อง (เลขจำนวนเต็ม) บน Windows ใช้ DirectShow จะเสถียร/เปิดเร็วกว่า
    if isinstance(src, int) and sys.platform.startswith("win"):
        cap = cv2.VideoCapture(src, cv2.CAP_DSHOW)
    else:
        cap = cv2.VideoCapture(src)
    if not cap.isOpened():
        print(f"[x] เปิดวิดีโอไม่ได้: {src}")
        sys.exit(1)

    in_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)) or 1280
    in_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)) or 720
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 0

    # ย่อขนาดเพื่อความเร็ว
    scale = min(1.0, args.max_width / in_w)
    W, H = int(in_w * scale), int(in_h * scale)
    focal = focal_from_width(W, args.hfov)

    writer = None
    if args.save:
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(args.save, fourcc, src_fps, (W, H))

    print(f"[ok] กำลังประมวลผล  {W}x{H}  | กด Q ออก, SPACE หยุดชั่วคราว")

    dist_hist = deque(maxlen=5)     # ระยะรถหน้าเพื่อคำนวณ TTC
    t_hist = deque(maxlen=5)
    fps_smooth = src_fps
    frame_i = 0
    paused = False

    while True:
        if not paused:
            ret, frame = cap.read()
            if not ret:
                break
            frame_i += 1
            if args.max_frames and frame_i > args.max_frames:
                break
            t0 = time.time()
            frame = cv2.resize(frame, (W, H))
            if args.skip and (frame_i % (args.skip + 1) != 0):
                continue   # ข้ามเฟรมนี้เพื่อให้ลื่นขึ้น (เฉพาะ realtime)

            res = model.predict(frame, imgsz=args.imgsz, conf=args.conf,
                                 classes=list(CLASSES.keys()), verbose=False)[0]

            objs = []           # สำหรับ data panel
            lead_dist = 999.0   # รถคันหน้าที่ใกล้สุดในเลนกลาง
            ped_in_lane = False
            red_light = False
            n = 0

            # เลนกลาง = ช่วงกว้าง 35%-65% ของจอ, ครึ่งล่าง
            lane_l, lane_r = 0.32 * W, 0.68 * W

            for b in res.boxes:
                cid = int(b.cls[0])
                if cid not in CLASSES:
                    continue
                conf = float(b.conf[0])
                x1, y1, x2, y2 = b.xyxy[0].tolist()
                name, real_h = CLASSES[cid]
                box_h = y2 - y1
                cx = (x1 + x2) / 2
                dist = estimate_distance(box_h, real_h, focal)
                n += 1

                # สี + การจัดประเภท
                color = C_DET
                in_lane = lane_l < cx < lane_r

                if cid in VULNERABLE_IDS and dist < PED_CRIT and in_lane:
                    ped_in_lane = True
                    color = C_CRIT
                elif cid == 9:  # ไฟจราจร
                    if is_red_light(frame, (x1, y1, x2, y2)):
                        red_light = True
                        color = C_CRIT
                        name = "RED light"
                elif cid in VEHICLE_IDS and in_lane:
                    if dist < lead_dist:
                        lead_dist = dist
                    if dist < CRIT_DIST:
                        color = C_CRIT
                    elif dist < WARN_DIST:
                        color = C_WARN

                label = f"{name} {conf:.2f}"
                show_dist = None if cid in (9, 11) else dist
                draw_box(frame, (x1, y1, x2, y2), label, show_dist, color)
                objs.append((f"{name}", dist, color))

            # คำนวณ TTC จากการเปลี่ยนแปลงระยะรถคันหน้า
            now = time.time()
            ttc = None
            dist_hist.append(lead_dist)
            t_hist.append(now)
            if len(dist_hist) >= 2 and lead_dist < 900:
                dd = dist_hist[0] - dist_hist[-1]      # ระยะที่ลดลง (เข้าใกล้)
                dt = t_hist[-1] - t_hist[0]
                if dt > 0 and dd > 0.1:                # กำลังเข้าใกล้
                    rel_speed = dd / dt                # m/s
                    ttc = lead_dist / rel_speed

            # ความเร็วโดยประมาณ (ไม่มี GPS -> ค่าจำลองจากความใกล้)
            speed = max(0, 70 - (70 - 15) * (1 - min(lead_dist, SAFE_DIST) / SAFE_DIST)) if lead_dist < 900 else 65

            line1, line2, ccol, gas, brk = decide(lead_dist, ttc, ped_in_lane, red_light)

            # วาด HUD
            # พื้นที่ขับได้ (drivable corridor) สีเขียวจาง
            corridor = np.array([[int(0.40*W), H], [int(0.60*W), H],
                                 [int(0.54*W), int(0.55*H)], [int(0.46*W), int(0.55*H)]], np.int32)
            ov = frame.copy()
            cv2.fillPoly(ov, [corridor], C_OK)
            frame = cv2.addWeighted(ov, 0.18, frame, 0.82, 0)
            cv2.polylines(frame, [corridor], True, C_HUD, 1)

            draw_command(frame, W, ccol, line1, line2)
            draw_fcw(frame, W, H, lead_dist, ttc, ccol)
            draw_telemetry(frame, H, speed, gas, brk)

            dt_proc = now - t0
            fps_now = 1.0 / dt_proc if dt_proc > 0 else 0
            fps_smooth = 0.9 * fps_smooth + 0.1 * fps_now
            objs.sort(key=lambda o: o[1])
            draw_datapanel(frame, W, fps_smooth, frame_i, total, objs)

            put_text(frame, "ADVISORY ONLY - not controlling the vehicle",
                     (14, H - 8), 0.4, C_WHITE, 1, bg=True)

            if writer:
                writer.write(frame)

        if not args.no_show:
            cv2.imshow("AutoDrive — Advisory ADAS", frame)
            key = cv2.waitKey(1) & 0xFF
            if key == ord("q"):
                break
            if key == ord(" "):
                paused = not paused
        elif frame_i % 30 == 0:
            print(f"\r  เฟรม {frame_i}/{total}  ({fps_smooth:4.1f} FPS)", end="")

    cap.release()
    if writer:
        writer.release()
        print(f"\n[ok] เซฟผลลัพธ์ -> {args.save}")
    cv2.destroyAllWindows()
    print("\n[done] จบการทำงาน")


if __name__ == "__main__":
    main()

package com.autodrive.app

/**
 * กันการเตือนรัว: เตือน "ครั้งเดียว" ตอนเหตุการณ์เริ่มเกิด (ขอบขาขึ้น)
 * แล้วต้องรอให้เหตุการณ์ "หายไปต่อเนื่อง" (clearFrames เฟรม) ก่อน ถึงจะ trigger ใหม่ได้
 *
 * เรียก check(key, present) ทุกเฟรมสำหรับทุก event เพื่ออัปเดตสถานะ
 *   - คืน true = ควรเตือน (ผู้เรียกต้อง markFired ถ้าเตือนจริง)
 */
class EventGate(private val clearFrames: Int = 10) {
    private val active = HashMap<String, Boolean>()
    private val absent = HashMap<String, Int>()

    fun check(key: String, present: Boolean): Boolean {
        val isActive = active[key] ?: false
        return if (present) {
            absent[key] = 0
            !isActive            // เริ่มเจอ (ยังไม่ active) = ขอบขาขึ้น -> ควรเตือน
        } else {
            val a = (absent[key] ?: 0) + 1
            absent[key] = a
            if (a >= clearFrames) active[key] = false   // หายไปนานพอ -> reset
            false
        }
    }

    fun markFired(key: String) { active[key] = true }
}

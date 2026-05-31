package com.autodrive.app

/**
 * กันการเตือนรัว + reset เฉพาะเมื่อวัตถุหายจากจอจริง ๆ
 *
 * check(key, trigger, onScreen) เรียกทุกเฟรมสำหรับทุก event:
 *   - trigger  = เงื่อนไขที่ "ควรเตือน" (เช่น ไฟแดง, รถใกล้)
 *   - onScreen = ยังเห็นวัตถุชนิดนั้นในจอไหม (เช่น ยังมีไฟจราจร/คน/รถคันหน้าอยู่)
 *
 * เตือนครั้งเดียวตอน trigger ขึ้น แล้วจะ "ไม่เตือนซ้ำ" ตราบใดที่ onScreen ยัง true
 * จะ reset (เตือนใหม่ได้) ก็ต่อเมื่อ onScreen เป็น false ต่อเนื่อง clearFrames เฟรม
 * (= วัตถุหายจากจอจริง ๆ)
 */
class EventGate(private val clearFrames: Int = 12) {
    private val active = HashMap<String, Boolean>()
    private val absent = HashMap<String, Int>()

    fun check(key: String, trigger: Boolean, onScreen: Boolean): Boolean {
        val isActive = active[key] ?: false
        if (isActive) {
            // เตือนไปแล้ว: reset ก็ต่อเมื่อวัตถุหายจากจอต่อเนื่องนานพอ
            if (onScreen) {
                absent[key] = 0
            } else {
                val a = (absent[key] ?: 0) + 1
                absent[key] = a
                if (a >= clearFrames) active[key] = false
            }
            return false
        }
        // ยังไม่ได้เตือน: ถ้าเงื่อนไขเตือนเกิดขึ้น = ควรเตือน
        return trigger
    }

    fun markFired(key: String) { active[key] = true; absent[key] = 0 }
}

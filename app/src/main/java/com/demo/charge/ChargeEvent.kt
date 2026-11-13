package com.demo.charge

/**
 * 充电提示音支持的全部事件。
 *
 * 每个事件都有：
 *   id       存偏好用的键
 *   label    界面上显示的名字
 *   rawName  res/raw 里的默认音效（用户没自定义时用它）
 */
enum class ChargeEvent(val id: String, val label: String, val rawName: String) {

    PLUG_IN("plug_in", "插电音", "plug_in"),
    PLUG_OUT("plug_out", "拔电音", "plug_out"),
    WIRELESS("wireless", "无线充音", "wireless"),
    FULL("full", "充满音", "full"),
    OVERHEAT("overheat", "过热音", "overheat"),
    LOW_21("low21", "低电量 21%", "low21"),
    LOW_11("low11", "低电量 11%", "low11"),
    LOW_3("low3", "低电量 3%", "low3"),
    SCREEN_OFF("screen_off", "熄屏音", "screen_off"),
    UNLOCK("unlock", "解锁音", "unlock");

    companion object {
        fun byId(id: String?): ChargeEvent =
            values().firstOrNull { it.id == id } ?: PLUG_IN
    }
}

package io.hafa.rmapikt

/** a reMarkable device's display */
public data class DeviceScreen(
    /** the marketing name */
    public val name: String,
    /** native portrait width in pixels */
    public val width: Int,
    /** native portrait height in pixels */
    public val height: Int,
    /** display density in dots per inch */
    public val dpi: Int,
)

/** a known reMarkable device, by model number */
public enum class DeviceModel {
    /** reMarkable 1 */
    RM100,

    /** reMarkable 2 */
    RM110,

    /** reMarkable Paper Pro */
    RM02A,

    /** reMarkable Paper Pro Move */
    RM03A,

    /** reMarkable Paper Pure */
    RM102,
}

/**
 * display specifications for every known device
 *
 * These feed the `customFit` zoom arithmetic: [DocumentContent.customZoomPageWidth] and
 * [DocumentContent.customZoomPageHeight] are the source page in device pixels
 * (`pagePoints * dpi / 72`), and the screen dimensions give the aspect ratio. Every model
 * is 3:4 except the Paper Pro Move, which is 9:16.
 */
public val deviceScreens: Map<DeviceModel, DeviceScreen> = mapOf(
    DeviceModel.RM100 to DeviceScreen("reMarkable 1", 1404, 1872, 226),
    DeviceModel.RM110 to DeviceScreen("reMarkable 2", 1404, 1872, 226),
    DeviceModel.RM02A to DeviceScreen("reMarkable Paper Pro", 1620, 2160, 229),
    DeviceModel.RM03A to DeviceScreen("reMarkable Paper Pro Move", 954, 1696, 264),
    DeviceModel.RM102 to DeviceScreen("reMarkable Paper Pure", 1404, 1872, 226),
)

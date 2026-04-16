import org.jetbrains.compose.web.css.*

/**
 * Dashboard CSS styles using Compose for Web CSS API.
 * Hand-rolled styles for a modern, dark-themed management interface.
 */
object DashboardStyles : StyleSheet() {

    // ========================================================================
    // Color Palette
    // ========================================================================

    private val colorBackground = Color("#0f0f1a")
    private val colorSurface = Color("#1a1a2e")
    private val colorSurfaceHover = Color("#252540")
    private val colorBorder = Color("#27273a")
    private val colorBorderHover = Color("#3f3f5a")
    private val colorText = Color("#e4e4e7")
    private val colorTextMuted = Color("#71717a")
    private val colorTextDim = Color("#52525b")
    private val colorPrimary = Color("#6366f1")
    private val colorPrimaryHover = Color("#4f46e5")
    private val colorSuccess = Color("#22c55e")
    private val colorWarning = Color("#f59e0b")
    private val colorError = Color("#ef4444")

    // ========================================================================
    // Layout
    // ========================================================================

    val appContainer by style {
        display(DisplayStyle.Flex)
        height(100.vh)
        overflow("hidden")
        backgroundColor(colorBackground)
        color(colorText)
        fontFamily("Inter", "-apple-system", "BlinkMacSystemFont", "Segoe UI", "Roboto", "sans-serif")
        fontSize(14.px)
        lineHeight("1.5")
    }

    // ========================================================================
    // Sidebar
    // ========================================================================

    val sidebar by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        width(260.px)
        height(100.vh)
        flexShrink(0)
        backgroundColor(colorSurface)
        property("border-right", "1px solid $colorBorder")
        property("transition", "width 0.2s ease, transform 0.25s ease")
        overflow("hidden")
        property("z-index", "200")
    }

    val sidebarCollapsed by style {
        width(72.px)
    }

    // Mobile: sidebar slides off-screen as an overlay
    val sidebarMobile by style {
        position(Position.Fixed)
        top(0.px)
        left(0.px)
        bottom(0.px)
        width(280.px)
        property("transform", "translateX(-100%)")
        property("z-index", "1000")
        property("box-shadow", "none")
    }

    val sidebarMobileOpen by style {
        property("transform", "translateX(0)")
        property("box-shadow", "4px 0 24px rgba(0, 0, 0, 0.5)")
    }

    val sidebarBackdrop by style {
        position(Position.Fixed)
        top(0.px)
        left(0.px)
        right(0.px)
        bottom(0.px)
        backgroundColor(Color("rgba(0, 0, 0, 0.5)"))
        property("z-index", "999")
        property("backdrop-filter", "blur(2px)")
    }

    val hamburgerButton by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        width(40.px)
        height(40.px)
        backgroundColor(Color.transparent)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(8.px)
        color(colorTextMuted)
        cursor("pointer")
        fontSize(18.px)
        property("transition", "all 0.15s ease")
        marginRight(12.px)

        self + hover style {
            backgroundColor(colorSurfaceHover)
            color(colorText)
        }
    }

    val sidebarHeader by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        alignItems(AlignItems.FlexStart)
        padding(24.px)
        gap(4.px)
        property("border-bottom", "1px solid $colorBorder")
    }

    val logo by style {
        fontSize(20.px)
        fontWeight(700)
        color(colorPrimary)
        letterSpacing((-0.5).px)
    }

    val logoSubtitle by style {
        fontSize(12.px)
        color(colorTextMuted)
        property("text-transform", "uppercase")
        letterSpacing(1.px)
    }

    val sidebarNav by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        padding(16.px, 12.px)
        gap(4.px)
        flex(1)
        overflow("auto")
    }

    val navItem by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(12.px)
        padding(12.px, 16.px)
        borderRadius(8.px)
        color(colorTextMuted)
        textDecoration("none")
        property("transition", "all 0.15s ease")

        self + hover style {
            backgroundColor(colorSurfaceHover)
            color(colorText)
        }
    }

    val navItemActive by style {
        backgroundColor(Color("#6366f115"))
        color(colorPrimary)

        self + hover style {
            backgroundColor(Color("#6366f120"))
            color(colorPrimary)
        }
    }

    val navIcon by style {
        fontSize(18.px)
        width(20.px)
        textAlign("center")
    }

    val navLabel by style {
        fontSize(14.px)
        fontWeight(500)
    }

    val sidebarFooter by style {
        padding(16.px)
        property("border-top", "1px solid $colorBorder")
    }

    val collapseButton by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        width(100.percent)
        padding(8.px)
        backgroundColor(Color.transparent)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(6.px)
        color(colorTextMuted)
        cursor("pointer")
        property("transition", "all 0.15s ease")

        self + hover style {
            backgroundColor(colorSurfaceHover)
            color(colorText)
            property("border-color", colorBorderHover.toString())
        }
    }

    // ========================================================================
    // Main Content
    // ========================================================================

    val mainContent by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        flex(1)
        height(100.vh)
        minWidth(0.px) // Prevent flex item from overflowing
        overflow("hidden")
    }

    val header by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.SpaceBetween)
        padding(24.px, 32.px)
        property("border-bottom", "1px solid $colorBorder")
        backgroundColor(colorSurface)
        flexShrink(0)
    }

    val pageTitle by style {
        fontSize(24.px)
        fontWeight(600)
        color(colorText)
        property("margin", "0")
    }

    val headerActions by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(16.px)
    }

    val statusIndicator by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(8.px)
        fontSize(13.px)
        color(colorTextMuted)
    }

    val statusDot by style {
        width(8.px)
        height(8.px)
        borderRadius(50.percent)
        backgroundColor(colorTextDim)
    }

    val statusOnline by style {
        backgroundColor(colorSuccess)
    }

    val statusError by style {
        backgroundColor(colorError)
    }

    val statusWarning by style {
        backgroundColor(colorWarning)
    }

    val pageContent by style {
        flex(1)
        padding(32.px)
        overflow("auto")
    }

    val pageContentMobile by style {
        padding(16.px)
    }

    // ========================================================================
    // Cards
    // ========================================================================

    val card by style {
        backgroundColor(colorSurface)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(12.px)
        overflow("hidden")
    }

    val cardGrid by style {
        display(DisplayStyle.Grid)
        property("grid-template-columns", "repeat(auto-fit, minmax(240px, 1fr))")
        gap(24.px)
        marginBottom(32.px)
    }

    val cardList by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(12.px)
    }

    val statCard by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(16.px)
        padding(24.px)
        backgroundColor(colorSurface)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(12.px)
        property("transition", "all 0.15s ease")

        self + hover style {
            property("border-color", colorBorderHover.toString())
            property("transform", "translateY(-2px)")
        }
    }

    val statIcon by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        width(48.px)
        height(48.px)
        borderRadius(10.px)
        fontSize(20.px)
    }

    val statContent by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(4.px)
    }

    val statValue by style {
        fontSize(28.px)
        fontWeight(700)
        color(colorText)
        lineHeight("1")
    }

    val statTitle by style {
        fontSize(13.px)
        color(colorTextMuted)
    }

    // ========================================================================
    // Bridge Cards
    // ========================================================================

    val bridgeCard by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(16.px)
        padding(20.px, 24.px)
        backgroundColor(colorSurface)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(12.px)
        property("transition", "all 0.15s ease")

        self + hover style {
            property("border-color", colorBorderHover.toString())
        }
    }

    val bridgeIcon by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        width(44.px)
        height(44.px)
        backgroundColor(colorSurfaceHover)
        borderRadius(10.px)
        fontSize(20.px)
        color(colorTextMuted)
    }

    val bridgeInfo by style {
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(4.px)
        flex(1)
    }

    val bridgeName by style {
        fontSize(15.px)
        fontWeight(600)
        color(colorText)
    }

    val bridgeStatus by style {
        fontSize(13.px)
        color(colorTextMuted)
    }

    // ========================================================================
    // Sections
    // ========================================================================

    val section by style {
        marginBottom(32.px)
    }

    val sectionTitle by style {
        fontSize(16.px)
        fontWeight(600)
        color(colorText)
        marginBottom(16.px)
        property("margin-top", "0")
    }

    // ========================================================================
    // Buttons
    // ========================================================================

    val button by style {
        display(DisplayStyle.Flex)
        property("display", "inline-flex")
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        gap(8.px)
        padding(10.px, 16.px)
        fontSize(14.px)
        fontWeight(500)
        borderRadius(8.px)
        border(0.px, LineStyle.None, Color.transparent)
        cursor("pointer")
        property("transition", "all 0.15s ease")
        textDecoration("none")
    }

    val buttonSmall by style {
        padding(8.px, 12.px)
        fontSize(13.px)
    }

    val buttonPrimary by style {
        backgroundColor(colorPrimary)
        color(Color.white)

        self + hover style {
            backgroundColor(colorPrimaryHover)
        }
    }

    val buttonOutline by style {
        backgroundColor(Color.transparent)
        border(1.px, LineStyle.Solid, colorBorder)
        color(colorText)

        self + hover style {
            backgroundColor(colorSurfaceHover)
            property("border-color", colorBorderHover.toString())
        }
    }

    val buttonDanger by style {
        backgroundColor(colorError)
        color(Color.white)

        self + hover style {
            backgroundColor(Color("#dc2626"))
        }
    }

    // ========================================================================
    // Forms
    // ========================================================================

    val input by style {
        width(100.percent)
        padding(10.px, 14.px)
        fontSize(14.px)
        backgroundColor(colorBackground)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(8.px)
        color(colorText)
        property("transition", "all 0.15s ease")

        self + focus style {
            property("outline", "none")
            property("border-color", colorPrimary.toString())
            property("box-shadow", "0 0 0 3px ${colorPrimary}20")
        }
    }

    val label by style {
        display(DisplayStyle.Block)
        fontSize(13.px)
        fontWeight(500)
        color(colorText)
        marginBottom(8.px)
    }

    val formGroup by style {
        marginBottom(20.px)
    }

    // ========================================================================
    // Tables
    // ========================================================================

    val tableWrapper by style {
        overflow("auto")
        property("-webkit-overflow-scrolling", "touch")
    }

    val table by style {
        width(100.percent)
        property("border-collapse", "collapse")
        property("min-width", "600px")
    }

    val tableHeader by style {
        textAlign("left")
        padding(12.px, 16.px)
        fontSize(12.px)
        fontWeight(600)
        color(colorTextMuted)
        property("text-transform", "uppercase")
        letterSpacing(0.5.px)
        property("border-bottom", "1px solid $colorBorder")
    }

    val tableCell by style {
        padding(16.px)
        property("border-bottom", "1px solid $colorBorder")
        color(colorText)
    }

    val tableRow by style {
        property("transition", "background-color 0.15s ease")

        self + hover style {
            backgroundColor(colorSurfaceHover)
        }
    }

    // ========================================================================
    // Utilities
    // ========================================================================

    val textMuted by style {
        color(colorTextMuted)
    }

    val textSuccess by style {
        color(colorSuccess)
    }

    val textWarning by style {
        color(colorWarning)
    }

    val textError by style {
        color(colorError)
    }

    val textCenter by style {
        textAlign("center")
    }

    val flexGrow by style {
        flex(1)
    }

    val gap8 by style {
        gap(8.px)
    }

    val gap16 by style {
        gap(16.px)
    }

    val mt16 by style {
        marginTop(16.px)
    }

    val mb16 by style {
        marginBottom(16.px)
    }

    // ========================================================================
    // Modal Dialog
    // ========================================================================

    val modalOverlay by style {
        position(Position.Fixed)
        top(0.px)
        left(0.px)
        right(0.px)
        bottom(0.px)
        backgroundColor(Color("rgba(0, 0, 0, 0.7)"))
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        property("z-index", "1000")
        property("backdrop-filter", "blur(4px)")
    }

    val modal by style {
        backgroundColor(colorSurface)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(16.px)
        padding(24.px)
        width(90.vw)
        maxWidth(500.px)
        maxHeight(80.vh)
        overflow("auto")
        property("box-shadow", "0 25px 50px -12px rgba(0, 0, 0, 0.5)")
    }

    val modalHeader by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.SpaceBetween)
        alignItems(AlignItems.Center)
        marginBottom(20.px)
    }

    val modalTitle by style {
        fontSize(18.px)
        fontWeight(600)
        color(colorText)
        property("margin", "0")
    }

    val modalCloseButton by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        width(32.px)
        height(32.px)
        backgroundColor(Color.transparent)
        border(0.px, LineStyle.None, Color.transparent)
        borderRadius(8.px)
        color(colorTextMuted)
        cursor("pointer")
        fontSize(18.px)
        property("transition", "all 0.15s ease")

        self + hover style {
            backgroundColor(colorSurfaceHover)
            color(colorText)
        }
    }

    val modalBody by style {
        marginBottom(24.px)
    }

    val modalFooter by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.FlexEnd)
        gap(12.px)
    }

    val helpText by style {
        fontSize(13.px)
        color(colorTextMuted)
        lineHeight("1.6")
        backgroundColor(colorBackground)
        padding(16.px)
        borderRadius(8.px)
        marginBottom(16.px)
        property("white-space", "pre-wrap")
    }

    val progressMessage by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(12.px)
        padding(16.px)
        backgroundColor(Color("#6366f115"))
        borderRadius(8.px)
        color(colorPrimary)
        fontSize(14.px)
    }

    val errorMessage by style {
        padding(16.px)
        backgroundColor(Color("#ef444415"))
        borderRadius(8.px)
        color(colorError)
        fontSize(14.px)
        marginBottom(16.px)
    }

    val successMessage by style {
        padding(16.px)
        backgroundColor(Color("#22c55e15"))
        borderRadius(8.px)
        color(colorSuccess)
        fontSize(14.px)
    }

    val spinner by style {
        width(16.px)
        height(16.px)
        border(2.px, LineStyle.Solid, Color("currentColor"))
        property("border-top-color", "transparent")
        borderRadius(50.percent)
        property("animation", "spin 0.8s linear infinite")
    }

    // ========================================================================
    // Toast Notifications
    // ========================================================================

    val toastContainer by style {
        position(Position.Fixed)
        bottom(24.px)
        right(24.px)
        left(24.px)
        display(DisplayStyle.Flex)
        flexDirection(FlexDirection.Column)
        gap(12.px)
        property("z-index", "1100")
        maxWidth(400.px)
        property("margin-left", "auto")
        property("pointer-events", "none")
    }

    val toast by style {
        backgroundColor(colorSurface)
        border(1.px, LineStyle.Solid, colorBorder)
        borderRadius(12.px)
        padding(16.px)
        property("box-shadow", "0 8px 32px rgba(0, 0, 0, 0.4)")
        property("pointer-events", "auto")
        property("animation", "slideIn 0.2s ease-out")
    }

    val toastHeader by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        gap(10.px)
        marginBottom(12.px)
    }

    val toastIcon by style {
        display(DisplayStyle.Flex)
        alignItems(AlignItems.Center)
        justifyContent(JustifyContent.Center)
        width(32.px)
        height(32.px)
        borderRadius(8.px)
        backgroundColor(Color("#f59e0b20"))
        color(colorWarning)
        fontSize(16.px)
        flexShrink(0)
    }

    val toastTitle by style {
        fontSize(14.px)
        fontWeight(600)
        color(colorText)
        flex(1)
    }

    val toastBody by style {
        fontSize(13.px)
        color(colorTextMuted)
        backgroundColor(colorBackground)
        padding(10.px, 12.px)
        borderRadius(6.px)
        marginBottom(12.px)
        property("white-space", "pre-wrap")
        property("word-break", "break-all")
        maxHeight(120.px)
        overflow("auto")
        fontFamily("JetBrains Mono", "monospace")
        lineHeight("1.4")
    }

    val toastActions by style {
        display(DisplayStyle.Flex)
        justifyContent(JustifyContent.FlexEnd)
        gap(8.px)
    }

    val buttonSuccess by style {
        backgroundColor(colorSuccess)
        color(Color.white)

        self + hover style {
            backgroundColor(Color("#16a34a"))
        }
    }
}

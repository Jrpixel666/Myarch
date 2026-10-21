package dev.myarch.runtime.theme

/**
 * The themes Myarch ships.
 *
 * These are the palettes Omarchy itself uses, so a user coming from an Omarchy
 * desktop sees the same colours on their phone. Importing a theme directory
 * from an Omarchy install is also supported -- see [ThemeImporter].
 */
public object BuiltInThemes {

    public val tokyoNight: OmarchyTheme = OmarchyTheme(
        id = "tokyo-night",
        name = "Tokyo Night",
        isLight = false,
        palette = Palette(
            background = "#1a1b26",
            surface = "#24283b",
            foreground = "#c0caf5",
            muted = "#565f89",
            accent = "#7aa2f7",
            accentAlt = "#bb9af7",
            warning = "#e0af68",
            error = "#f7768e",
            success = "#9ece6a",
            ansi = listOf(
                "#15161e", "#f7768e", "#9ece6a", "#e0af68",
                "#7aa2f7", "#bb9af7", "#7dcfff", "#a9b1d6",
                "#414868", "#f7768e", "#9ece6a", "#e0af68",
                "#7aa2f7", "#bb9af7", "#7dcfff", "#c0caf5",
            ),
        ),
        wallpaper = "tokyo-night.png",
    )

    public val catppuccinMocha: OmarchyTheme = OmarchyTheme(
        id = "catppuccin",
        name = "Catppuccin Mocha",
        isLight = false,
        palette = Palette(
            background = "#1e1e2e",
            surface = "#313244",
            foreground = "#cdd6f4",
            muted = "#6c7086",
            accent = "#89b4fa",
            accentAlt = "#cba6f7",
            warning = "#f9e2af",
            error = "#f38ba8",
            success = "#a6e3a1",
            ansi = listOf(
                "#45475a", "#f38ba8", "#a6e3a1", "#f9e2af",
                "#89b4fa", "#f5c2e7", "#94e2d5", "#bac2de",
                "#585b70", "#f38ba8", "#a6e3a1", "#f9e2af",
                "#89b4fa", "#f5c2e7", "#94e2d5", "#a6adc8",
            ),
        ),
        wallpaper = "catppuccin.png",
    )

    public val gruvbox: OmarchyTheme = OmarchyTheme(
        id = "gruvbox",
        name = "Gruvbox Dark",
        isLight = false,
        palette = Palette(
            background = "#282828",
            surface = "#3c3836",
            foreground = "#ebdbb2",
            muted = "#928374",
            accent = "#83a598",
            accentAlt = "#d3869b",
            warning = "#fabd2f",
            error = "#fb4934",
            success = "#b8bb26",
            ansi = listOf(
                "#282828", "#cc241d", "#98971a", "#d79921",
                "#458588", "#b16286", "#689d6a", "#a89984",
                "#928374", "#fb4934", "#b8bb26", "#fabd2f",
                "#83a598", "#d3869b", "#8ec07c", "#ebdbb2",
            ),
        ),
        wallpaper = "gruvbox.png",
    )

    public val nord: OmarchyTheme = OmarchyTheme(
        id = "nord",
        name = "Nord",
        isLight = false,
        palette = Palette(
            background = "#2e3440",
            surface = "#3b4252",
            foreground = "#eceff4",
            muted = "#616e88",
            accent = "#88c0d0",
            accentAlt = "#b48ead",
            warning = "#ebcb8b",
            error = "#bf616a",
            success = "#a3be8c",
            ansi = listOf(
                "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b",
                "#81a1c1", "#b48ead", "#88c0d0", "#e5e9f0",
                "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b",
                "#81a1c1", "#b48ead", "#8fbcbb", "#eceff4",
            ),
        ),
        wallpaper = "nord.png",
    )

    public val everforest: OmarchyTheme = OmarchyTheme(
        id = "everforest",
        name = "Everforest Dark",
        isLight = false,
        palette = Palette(
            background = "#2d353b",
            surface = "#343f44",
            foreground = "#d3c6aa",
            muted = "#859289",
            accent = "#a7c080",
            accentAlt = "#d699b6",
            warning = "#dbbc7f",
            error = "#e67e80",
            success = "#a7c080",
            ansi = listOf(
                "#343f44", "#e67e80", "#a7c080", "#dbbc7f",
                "#7fbbb3", "#d699b6", "#83c092", "#d3c6aa",
                "#868d80", "#e67e80", "#a7c080", "#dbbc7f",
                "#7fbbb3", "#d699b6", "#83c092", "#d3c6aa",
            ),
        ),
        wallpaper = "everforest.png",
    )

    public val rosePine: OmarchyTheme = OmarchyTheme(
        id = "rose-pine",
        name = "Rose Pine",
        isLight = false,
        palette = Palette(
            background = "#191724",
            surface = "#1f1d2e",
            foreground = "#e0def4",
            muted = "#6e6a86",
            accent = "#9ccfd8",
            accentAlt = "#c4a7e7",
            warning = "#f6c177",
            error = "#eb6f92",
            success = "#31748f",
            ansi = listOf(
                "#26233a", "#eb6f92", "#31748f", "#f6c177",
                "#9ccfd8", "#c4a7e7", "#ebbcba", "#e0def4",
                "#6e6a86", "#eb6f92", "#31748f", "#f6c177",
                "#9ccfd8", "#c4a7e7", "#ebbcba", "#e0def4",
            ),
        ),
        wallpaper = "rose-pine.png",
    )

    public val matteBlack: OmarchyTheme = OmarchyTheme(
        id = "matte-black",
        name = "Matte Black",
        isLight = false,
        palette = Palette(
            background = "#121212",
            surface = "#1c1c1c",
            foreground = "#eaeaea",
            muted = "#8a8a8a",
            accent = "#8cc2dd",
            accentAlt = "#c79bf0",
            warning = "#d2a379",
            error = "#ee6a70",
            success = "#99c794",
            ansi = listOf(
                "#121212", "#ee6a70", "#99c794", "#d2a379",
                "#8cc2dd", "#c79bf0", "#8abeb7", "#d4d4d4",
                "#333333", "#ee6a70", "#99c794", "#d2a379",
                "#8cc2dd", "#c79bf0", "#8abeb7", "#eaeaea",
            ),
        ),
        wallpaper = "matte-black.png",
    )

    public val catppuccinLatte: OmarchyTheme = OmarchyTheme(
        id = "catppuccin-latte",
        name = "Catppuccin Latte",
        isLight = true,
        palette = Palette(
            background = "#eff1f5",
            surface = "#e6e9ef",
            foreground = "#4c4f69",
            muted = "#9ca0b0",
            accent = "#1e66f5",
            accentAlt = "#8839ef",
            warning = "#df8e1d",
            error = "#d20f39",
            success = "#40a02b",
            ansi = listOf(
                "#5c5f77", "#d20f39", "#40a02b", "#df8e1d",
                "#1e66f5", "#ea76cb", "#179299", "#acb0be",
                "#6c6f85", "#d20f39", "#40a02b", "#df8e1d",
                "#1e66f5", "#ea76cb", "#179299", "#bcc0cc",
            ),
        ),
        wallpaper = "catppuccin-latte.png",
    )

    /** Every bundled theme, in the order the picker shows them. */
    public val all: List<OmarchyTheme> = listOf(
        tokyoNight,
        catppuccinMocha,
        gruvbox,
        nord,
        everforest,
        rosePine,
        matteBlack,
        catppuccinLatte,
    )

    /** The theme used before the user picks one. */
    public val default: OmarchyTheme = tokyoNight

    public fun byId(id: String): OmarchyTheme? = all.firstOrNull { it.id == id }
}

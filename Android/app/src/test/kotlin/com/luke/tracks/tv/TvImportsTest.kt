package com.luke.tracks.tv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV screens take focus from tv-material's components. Material 3's touch components have no
 * TV focus states, so none of them may be used under ui/tv.
 */
class TvImportsTest {
    private val touchComponents = setOf(
        "Button", "ElevatedButton", "FilledTonalButton", "OutlinedButton", "TextButton", "SplitButtonLayout",
        "IconButton", "FilledIconButton", "FilledTonalIconButton", "OutlinedIconButton",
        "IconToggleButton", "FilledIconToggleButton", "ToggleButton",
        "FloatingActionButton", "SmallFloatingActionButton", "LargeFloatingActionButton", "ExtendedFloatingActionButton",
        "Card", "ElevatedCard", "OutlinedCard", "ListItem",
        "NavigationBar", "NavigationBarItem", "NavigationRail", "NavigationRailItem", "WideNavigationRail",
        "NavigationDrawerItem", "ModalNavigationDrawer", "PermanentNavigationDrawer", "DismissibleNavigationDrawer",
        "TopAppBar", "CenterAlignedTopAppBar", "MediumTopAppBar", "LargeTopAppBar", "BottomAppBar",
        "ModalBottomSheet", "BottomSheetScaffold", "Scaffold",
        "AssistChip", "FilterChip", "InputChip", "SuggestionChip",
        "Tab", "TabRow", "PrimaryTabRow", "SecondaryTabRow", "ScrollableTabRow",
        "Switch", "Checkbox", "RadioButton", "Slider", "RangeSlider",
        "DropdownMenu", "DropdownMenuItem", "ExposedDropdownMenuBox", "AlertDialog", "BasicAlertDialog",
        "TextField", "OutlinedTextField", "SearchBar", "DockedSearchBar",
        "SegmentedButton", "SingleChoiceSegmentedButtonRow", "MultiChoiceSegmentedButtonRow",
        "HorizontalFloatingToolbar", "VerticalFloatingToolbar",
    )

    private val tvSources = File("src/main/kotlin/com/luke/tracks/ui/tv")

    @Test fun noMaterialTouchComponentsUnderUiTv() {
        assertTrue("ui/tv not found from ${File(".").absolutePath}", tvSources.isDirectory)
        val material = Regex("""^import\s+androidx\.compose\.material3\.([A-Za-z0-9_.*]+)""")
        val offenders = tvSources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val name = material.find(line.trim())?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                val simple = name.substringBefore(" ").substringAfterLast('.')
                if (simple == "*" || simple in touchComponents) "${file.relativeTo(tvSources)}:${i + 1}: $name" else null
            }
        }.toList()
        assertTrue("Use tv-material instead:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun noFullyQualifiedTouchComponentsEither() {
        val qualified = Regex("""androidx\.compose\.material3\.([A-Z][A-Za-z0-9]*)\s*\(""")
        val offenders = tvSources.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            qualified.findAll(file.readText()).map { it.groupValues[1] }.filter { it in touchComponents }.map { "${file.name}: $it" }
        }.toList()
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }
}

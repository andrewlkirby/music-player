package com.musicplayer.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.musicplayer.presentation.theme.AppIcons

/**
 * TopAppBar that swaps its title for an inline text field while searching,
 * so each browse screen can filter its own already-loaded list in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableTopAppBar(
    title: @Composable () -> Unit,
    isSearching: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    placeholder: String,
    navigationIcon: @Composable () -> Unit = {},
    extraActions: @Composable () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(isSearching) {
        if (isSearching) focusRequester.requestFocus()
    }

    TopAppBar(
        title = {
            if (isSearching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            } else {
                title()
            }
        },
        navigationIcon = {
            if (isSearching) {
                IconButton(onClick = {
                    onQueryChange("")
                    onSearchToggle(false)
                }) {
                    Icon(AppIcons.ArrowBack, "Close search")
                }
            } else {
                navigationIcon()
            }
        },
        actions = {
            if (isSearching) {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(AppIcons.Clear, "Clear")
                    }
                }
            } else {
                IconButton(onClick = { onSearchToggle(true) }) {
                    Icon(AppIcons.Search, "Search")
                }
                extraActions()
            }
        }
    )
}

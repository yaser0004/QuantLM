package com.quantlm.yaser.presentation.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Fix [2.3]: single composable for tappable URLs (deduplicates ModelsScreen / download UI patterns).
 */
@Composable
fun ClickableLink(
    url: String,
    linkText: String,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val annotated = AnnotatedString(
        text = linkText,
        spanStyles = listOf(
            AnnotatedString.Range(
                item = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                ),
                start = 0,
                end = linkText.length
            )
        )
    )
    Row(
        modifier = modifier
            .clickable { uriHandler.openUri(url) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

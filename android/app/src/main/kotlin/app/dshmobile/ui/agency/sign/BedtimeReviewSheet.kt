package app.dshmobile.ui.agency.sign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.agency.core.brain.handlers.ReviewDesk
import app.dshmobile.R
import app.dshmobile.ui.kit.PinkButton
import app.dshmobile.ui.kit.Sakura

/**
 * 睡前回顧 after the sign-out (T19): one thing done well today and one thing to fix tomorrow, sent together (he may also answer
 * the two questions in the conversation). When the first is already answered only the second is asked.
 */
@Composable
fun BedtimeReviewSheet(open: ReviewDesk.Open, onSend: (good: String, fix: String) -> Unit) {
    var good by rememberSaveable { mutableStateOf("") }
    var fix by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Sakura.White, RoundedCornerShape(20.dp)).padding(16.dp)
            .testTag(SignTags.REVIEW),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ag_review_title), color = Sakura.Text, fontSize = 16.sp)
        if (open == ReviewDesk.Open.GOOD) {
            OutlinedTextField(
                value = good, onValueChange = { good = it.take(ReviewDesk.MAX_CHARS) }, singleLine = true,
                label = { Text(stringResource(R.string.ag_review_good)) }, modifier = Modifier.fillMaxWidth().testTag(SignTags.GOOD),
            )
        }
        OutlinedTextField(
            value = fix, onValueChange = { fix = it.take(ReviewDesk.MAX_CHARS) }, singleLine = true,
            label = { Text(stringResource(R.string.ag_review_fix)) }, modifier = Modifier.fillMaxWidth().testTag(SignTags.FIX),
        )
        val ready = fix.isNotBlank() && (open == ReviewDesk.Open.FIX || good.isNotBlank())
        PinkButton(stringResource(R.string.ag_review_send), enabled = ready, onClick = { onSend(good, fix) }, modifier = Modifier.testTag(SignTags.REVIEW_SEND))
    }
}

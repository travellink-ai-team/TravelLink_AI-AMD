package com.example.travellink_ai.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.travellink_ai.ui.theme.DesignTokens
import kotlinx.coroutines.launch

private data class WelcomePage(val emoji: String, val title: String, val body: String)

private val PAGES = listOf(
    WelcomePage("🗺️", "AI 幫你排台東小旅行",
        "選好日期、人數和喜好，AI 從台東在地景點與餐廳排出一日或兩天一夜的行程，營業時間、車程和用餐時段都幫你算好。"),
    WelcomePage("👥", "和旅伴一起規劃",
        "建立共編行程、分享邀請碼，每個人填自己想去的景點和預算，行程的修改即時同步。"),
    WelcomePage("📍", "出發後逐站打卡",
        "開始行程後依序導航、到站打卡。想多待一下就調整離開時間，後面的行程自動順延；停車場、廁所和天氣也都查得到。"),
    WelcomePage("📸", "留下旅程回憶",
        "在景點拍的照片會歸到那一站，和旅伴共用同一本相簿，還能做成 IG 九宮格。旅程結束後還能做成 IG Reels 回顧短片，可以設定成自動幫你做好。")
)

/** 第一次打開 App（或側欄「使用教學」）時的歡迎輪播 */
@Composable
fun WelcomeCarousel(onDone: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val isLast = pager.currentPage == PAGES.lastIndex
    // 返回鍵：回上一頁；第一頁就當作看完
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(DesignTokens.Bg)
            .systemBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            if (!isLast) TextButton(onClick = onDone) { Text("跳過", color = DesignTokens.Ink2, fontSize = 16.sp) }
            else Spacer(Modifier.height(48.dp))
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            val page = PAGES[i]
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(140.dp).clip(CircleShape).background(DesignTokens.AccentLight),
                    contentAlignment = Alignment.Center
                ) { Text(page.emoji, fontSize = 64.sp) }
                Spacer(Modifier.height(32.dp))
                Text(page.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    color = DesignTokens.Ink, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Text(page.body, fontSize = 16.sp, lineHeight = 25.sp,
                    color = DesignTokens.Ink2, textAlign = TextAlign.Center)
            }
        }

        // 頁碼點
        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            PAGES.indices.forEach { i ->
                Box(
                    Modifier
                        .size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) DesignTokens.Accent else DesignTokens.Border)
                )
            }
        }

        Button(
            onClick = { if (isLast) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(52.dp),
            shape = RoundedCornerShape(DesignTokens.Radius.Field),
            colors = ButtonDefaults.buttonColors(containerColor = DesignTokens.Accent)
        ) {
            Text(if (isLast) "開始使用" else "下一步", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

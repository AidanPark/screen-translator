package com.galaxy.airviewdictionary.ui.screen.onboarding


import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.ui.common.MP4Player
import com.galaxy.airviewdictionary.ui.common.safeSystemBarsPadding
import com.galaxy.airviewdictionary.ui.screen.intro.SplashActivity
import com.galaxy.airviewdictionary.ui.theme.ScreenTranslatorTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch


@AndroidEntryPoint
class OnBoardingActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, OnBoardingActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private val viewModel: OnBoardingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            ScreenTranslatorTheme {
                val backgroundColor = Color(0xFF212121) // 짙은 회색

                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                    window.statusBarColor = backgroundColor.toArgb()
                    window.navigationBarColor = backgroundColor.toArgb()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = backgroundColor
                ) {
                    OnboardingPager(backgroundColor)
                }
            }
        }
    }

    @Composable
    fun OnboardingPager(backgroundColor: Color) {
        val pages: List<@Composable () -> Unit> = listOf(
            { OnBoardingPage(TextDetectMode.WORD) },
            { OnBoardingPage(TextDetectMode.SENTENCE) },
            { OnBoardingPage(TextDetectMode.PARAGRAPH) },
            { OnBoardingPage(TextDetectMode.SELECT) },
            { OnBoardingPage(TextDetectMode.FIXED_AREA) },
        )
        val context = LocalContext.current

        val pagerState = rememberPagerState(
            pageCount = { pages.size },
            initialPage = 0
        )
        val coroutineScope = rememberCoroutineScope()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor) // 🔹 전체 배경색 적용
                .padding(24.dp)
                .safeSystemBarsPadding(), // ✨ 상태바 + 내비게이션바 패딩 (위장 프레임워크 기기 우회 포함)
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                pages[page]()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left - Previous Button
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                        },
                        enabled = pagerState.currentPage > 0
                    ) {
                        Text(
                            text = "PREV",
                            color = Color.White
                        )
                    }
                }

                // Center - Indicator (Fixed center)
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pages.forEachIndexed { index, _ ->
                            val isSelected = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(8.dp)
                                    .background(
                                        color = if (isSelected) Color.White else Color.Gray,
                                        shape = MaterialTheme.shapes.small
                                    )
                            )
                        }
                    }
                }

                // Right - Next or Start Button
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                if (pagerState.currentPage < pages.lastIndex) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else {
                                    viewModel.preferenceRepository.update(PreferenceRepository.WAS_TRAILER_SHOWN, true)
                                    SplashActivity.start(context = context)
                                    (context as OnBoardingActivity).finish()
                                }
                            }
                        }
                    ) {
                        Text(
                            text = if (pagerState.currentPage == pages.lastIndex) "START" else "NEXT",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun OnBoardingPage(textDetectMode: TextDetectMode) {

        val configuration = LocalConfiguration.current
        val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        if (isPortrait) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.5f)
//                .background(Color(0x4422f4ff))
                    ,
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
//                    .background(Color(0x4422f4ff))
                    ) {
                        Row(
                            modifier = Modifier.wrapContentSize(),
                            verticalAlignment = Alignment.CenterVertically,
//                    horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                painter = painterResource(textDetectMode.iconResourceId),
                                contentDescription = textDetectMode.text,
                                modifier = Modifier.size(24.dp),
                                tint = Color.White
                            )
                            Text(
                                text = textDetectMode.text,
                                modifier = Modifier.padding(start = 4.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        MP4Player(textDetectMode.videoResourceId)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.5f)
//                            .background(Color(0x44ff44ff))
                    ,
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        textAlign = TextAlign.Center,
                        text = stringResource(id = textDetectMode.descriptionResourceId),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp)
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .wrapContentWidth()
                        .padding(start = 50.dp)
//                            .background(Color(0x4422f4ff))
                    ,
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                    ) {
                        Row(
                            modifier = Modifier.wrapContentSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(textDetectMode.iconResourceId),
                                contentDescription = textDetectMode.text,
                                modifier = Modifier.size(24.dp),
                                tint = Color.White
                            )
                            Text(
                                text = textDetectMode.text,
                                modifier = Modifier.padding(start = 4.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        MP4Player(textDetectMode.videoResourceId)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
//                            .background(Color(0x44ff44ff))
                    ,
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        textAlign = TextAlign.Center,
                        text = stringResource(id = textDetectMode.descriptionResourceId),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp)
                    )
                }
            }
        }


    }
}






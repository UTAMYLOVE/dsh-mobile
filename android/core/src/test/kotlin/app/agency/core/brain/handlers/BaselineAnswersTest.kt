package app.agency.core.brain.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The strict answer readers (the old AgencyTest cases, moved with the code, plus the T07 ones). */
class BaselineAnswersTest {
    @Test fun strictBodyAnswers() {
        assertEquals(186.0 to 95.5, BaselineAnswers.body("身高 186 體重 95.5"))
        assertEquals(186.0 to 95.5, BaselineAnswers.body("186 95.5"))
        assertEquals(186.0 to 95.5, BaselineAnswers.body("186公分 95.5公斤"))
        assertEquals(null to 95.5, BaselineAnswers.body("體重 95.5"))
        assertEquals(186.0 to null, BaselineAnswers.body("身高一百八十六"))
        assertNull(BaselineAnswers.body("晚餐 150 元"))
        assertNull(BaselineAnswers.body("150 2"))
        assertNull(BaselineAnswers.body("身高 300 體重 94"))
    }

    @Test fun aPronounBeforeTheAnswerStillAnswers() {
        assertEquals(186.0 to 94.0, BaselineAnswers.body("我身高 186 體重 94"))
        assertEquals(null to 94.0, BaselineAnswers.body("我的體重 94"))
    }

    @Test fun pushUps() {
        assertEquals(25, BaselineAnswers.pushups("伏地挺身 25 下"))
        assertEquals(25, BaselineAnswers.pushups("25"))
        assertEquals(20, BaselineAnswers.pushups("二十下"))
        assertEquals(10, BaselineAnswers.pushups("十下"))
        assertNull(BaselineAnswers.pushups("等一下 10 分鐘"))
        assertNull(BaselineAnswers.pushups("一下"))
        assertNull(BaselineAnswers.pushups("晚點再吃宵夜"))
    }

    @Test fun plank() {
        assertEquals(90, BaselineAnswers.plank("棒式 90 秒"))
        assertEquals(90, BaselineAnswers.plank("1 分 30 秒"))
        assertEquals(90, BaselineAnswers.plank("一分半"))
        assertEquals(60, BaselineAnswers.plank("60"))
        assertNull(BaselineAnswers.plank("晚餐吃了給力盒子 629"))
        assertNull(BaselineAnswers.plank("2"))
    }

    @Test fun skipAndConfirmWordsAreExactAfterTrimming() {
        assertEquals("跳過", BaselineAnswers.clean(" 跳過。"))
        assertEquals("對", BaselineAnswers.clean("對！"))
        assertNull(BaselineAnswers.SKIP.firstOrNull { it == BaselineAnswers.clean("晚點再吃宵夜") })
    }
}

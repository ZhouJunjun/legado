package io.legado.app.ui.book.read.page.delegate

import android.view.MotionEvent
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.screenshot

abstract class HorizontalPageDelegate(readView: ReadView) : PageDelegate(readView) {

    protected var curRecorder = CanvasRecorderFactory.create()
    protected var prevRecorder = CanvasRecorderFactory.create()
    protected var nextRecorder = CanvasRecorderFactory.create()
    private val slopSquare get() = readView.pageSlopSquare2

    override fun setDirection(direction: PageDirection) {
        super.setDirection(direction)
        setBitmap()
    }

    open fun setBitmap() {
        when (mDirection) {
            PageDirection.PREV -> {
                prevPage.screenshot(prevRecorder)
                curPage.screenshot(curRecorder)
            }

            PageDirection.NEXT -> {
                nextPage.screenshot(nextRecorder)
                curPage.screenshot(curRecorder)
            }

            else -> Unit
        }
    }

    fun upRecorder() {
        curRecorder.recycle()
        prevRecorder.recycle()
        nextRecorder.recycle()
        curRecorder = CanvasRecorderFactory.create()
        prevRecorder = CanvasRecorderFactory.create()
        nextRecorder = CanvasRecorderFactory.create()
    }

    override fun onTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                abortAnim()
            }

            MotionEvent.ACTION_MOVE -> {
                onScroll(event)
            }

            MotionEvent.ACTION_UP -> {
                onAnimStart(readView.defaultAnimationSpeed)
            }

            MotionEvent.ACTION_CANCEL -> {
                // 手势被系统抢走: 侧边/底部返回手势、多任务手势、下拉通知栏等会先给
                // 本视图派发 DOWN + MOVE, 等系统确认要接管时再补一个 CANCEL。
                // 原先这里与 ACTION_UP 合并, 等于把「系统夺走手势」当成「用户抬手」,
                // 于是斜着划出屏幕边缘(横向位移早于纵向位移)时的横向分量被动画补成整页,
                // 表现为从阅读页返回首页时偷偷翻了一页。CANCEL 只回滚, 不提交翻页。
                cancelAnim()
            }
        }
    }

    /**
     * 手势被系统接管时回滚本页已产生的位移, 保留原页不翻页。
     * 无位移的 CANCEL 保持与 DOWN 相同的既有行为, 不额外重绘。
     */
    open fun cancelAnim() {
        if (!isMoved && !isRunning && !isStarted) {
            abortAnim()
            return
        }
        // isCancel = true 使 abortAnim 只中止滚动, 不执行 fillPage —— 即不提交翻页
        isCancel = true
        abortAnim()
        readView.invalidate()
    }

    private fun onScroll(event: MotionEvent) {
        val action: Int = event.action
        val pointerUp =
            action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_POINTER_UP
        val skipIndex = if (pointerUp) event.actionIndex else -1
        // Determine focal point
        var sumX = 0f
        var sumY = 0f
        val count: Int = event.pointerCount
        for (i in 0 until count) {
            if (skipIndex == i) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        val div = if (pointerUp) count - 1 else count
        val focusX = sumX / div
        val focusY = sumY / div
        //判断是否移动了
        if (!isMoved) {
            val deltaX = (focusX - startX).toInt()
            val deltaY = (focusY - startY).toInt()
            val distance = deltaX * deltaX + deltaY * deltaY
            isMoved = distance > slopSquare
            if (isMoved) {
                readView.markReadPositionChanged()
                if (focusX - startX > 0) {
                    //如果上一页不存在
                    if (!hasPrev()) {
                        noNext = true
                        return
                    }
                    setDirection(PageDirection.PREV)
                } else {
                    //如果不存在表示没有下一页了
                    if (!hasNext()) {
                        noNext = true
                        return
                    }
                    setDirection(PageDirection.NEXT)
                }
                readView.setStartPoint(focusX, focusY, false)
            }
        }
        if (isMoved) {
            // Allow switching direction while keeping finger down: if user reverses
            // across the start point we switch mDirection so the UI follows the finger.
            val delta = focusX - startX
            if (mDirection == PageDirection.NEXT && delta > 0) {
                if (!hasPrev()) {
                    noNext = true
                    return
                }
                setDirection(PageDirection.PREV)
                readView.setStartPoint(focusX, focusY, false)
            } else if (mDirection == PageDirection.PREV && delta < 0) {
                if (!hasNext()) {
                    noNext = true
                    return
                }
                setDirection(PageDirection.NEXT)
                readView.setStartPoint(focusX, focusY, false)
            }
            isCancel = if (mDirection == PageDirection.NEXT) focusX > lastX else focusX < lastX
            isRunning = true
            //设置触摸点
            readView.setTouchPoint(focusX, focusY)
        }
    }

    override fun abortAnim() {
        isStarted = false
        isMoved = false
        isRunning = false
        if (!scroller.isFinished) {
            readView.isAbortAnim = true
            scroller.abortAnimation()
            if (!isCancel) {
                readView.fillPage(mDirection)
                readView.invalidate()
            }
        } else {
            readView.isAbortAnim = false
        }
    }

    override fun nextPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasNext()) return
        setDirection(PageDirection.NEXT)
        val y = when {
            startY > viewHeight / 2 -> viewHeight.toFloat() * 0.9f
            else -> 1f
        }
        readView.setStartPoint(viewWidth.toFloat() * 0.9f, y, false)
        onAnimStart(animationSpeed)
    }

    override fun prevPageByAnim(animationSpeed: Int) {
        abortAnim()
        if (!hasPrev()) return
        setDirection(PageDirection.PREV)
        readView.setStartPoint(0f, viewHeight.toFloat(), false)
        onAnimStart(animationSpeed)
    }

    override fun onDestroy() {
        super.onDestroy()
        prevRecorder.recycle()
        curRecorder.recycle()
        nextRecorder.recycle()
    }

}

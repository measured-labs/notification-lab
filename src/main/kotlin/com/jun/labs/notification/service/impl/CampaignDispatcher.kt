package com.jun.labs.notification.service.impl

import com.jun.labs.notification.service.NotificationProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 캠페인 디스패처(ADR-000 §3) — 캠페인마다 디스패처 스레드 1개가 대상 id를 차례로 만들어
 * 발송 풀(고정 pool-size 스레드 · 큐 pool-size × 2 — 모든 캠페인이 함께 쓴다)에 제출한다.
 * 큐가 차면 빈자리가 날 때까지 기다린다(블로킹 제출 — 거절 없음 · 작업이 한꺼번에 쌓이지 않는다).
 * 제출한 targetCount개가 모두 끝나면 COMPLETED. 서버가 도중에 죽으면 RUNNING으로 남는다(재개 없음).
 */
@Component
class CampaignDispatcher(
    private val campaignTransactions: CampaignTransactions,
    properties: NotificationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val poolSize = properties.campaign.poolSize

    private val sendPool = ThreadPoolExecutor(
        poolSize,
        poolSize,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(poolSize * 2),
        namedDaemon("campaign-send-"),
        BlockingSubmit,
    )

    private val dispatchers: MutableSet<Thread> = ConcurrentHashMap.newKeySet()

    /** 디스패처 스레드를 띄우고 바로 돌아온다 */
    fun dispatch(plan: CampaignPlan) {
        val thread = Thread.ofPlatform()
            .name("campaign-dispatcher-${plan.campaignId}")
            .daemon(true)
            .unstarted { run(plan) }
        dispatchers += thread
        thread.start()
    }

    /** 발송 풀 큐에 쌓인 작업 수(최대 pool-size × 2) */
    fun queuedTasks(): Int = sendPool.queue.size

    /** 발송 풀에서 실행 중인 작업 수(최대 pool-size) */
    fun activeTasks(): Int = sendPool.activeCount

    private fun run(plan: CampaignPlan) {
        val remaining = CountDownLatch(plan.targetCount)
        val failures = AtomicInteger()
        try {
            for (i in 0 until plan.targetCount) {
                val userId = plan.userIdStart + i
                sendPool.execute {
                    try {
                        campaignTransactions.sendTarget(plan, userId)
                    } catch (e: Exception) {
                        // 발송사 실패는 FAILED 행으로 끝난다 — 여기 오는 것은 DB 오류 · 종료 중 인터럽트 등(그 대상의 행은 롤백되어 없다).
                        // 같은 원인이 대상마다 반복되므로 스택은 첫 1건만, 나머지는 수만 센다
                        if (failures.incrementAndGet() == 1) {
                            log.error("캠페인 {} 대상 {} 처리 예외(첫 건)", plan.campaignId, userId, e)
                        }
                    } finally {
                        remaining.countDown()
                    }
                }
            }
            remaining.await()
            reportFailures(plan, failures)
            campaignTransactions.complete(plan.campaignId)
        } catch (e: InterruptedException) {
            reportFailures(plan, failures)
            log.warn("캠페인 {} 디스패치 중단 — RUNNING으로 남는다", plan.campaignId)
            Thread.currentThread().interrupt()
        } catch (e: RejectedExecutionException) {
            reportFailures(plan, failures)
            log.warn("캠페인 {} 디스패치 중단(발송 풀 종료) — RUNNING으로 남는다", plan.campaignId)
        } catch (e: Exception) {
            log.error("캠페인 {} 디스패치 실패 — RUNNING으로 남는다", plan.campaignId, e)
        } finally {
            dispatchers -= Thread.currentThread()
        }
    }

    private fun reportFailures(plan: CampaignPlan, failures: AtomicInteger) {
        val n = failures.get()
        if (n > 0) log.error("캠페인 {} 대상 처리 예외 {}건 — 그 대상의 알림 행은 없다", plan.campaignId, n)
    }

    /** 제출을 멈추고(디스패처 인터럽트) 실행 중 · 대기 작업에 잠시 시간을 준 뒤 남은 것을 인터럽트한다 */
    @PreDestroy
    fun shutdown() {
        dispatchers.forEach(Thread::interrupt)
        sendPool.shutdown()
        try {
            if (!sendPool.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) sendPool.shutdownNow()
        } catch (e: InterruptedException) {
            sendPool.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /** 큐가 차면 빈자리가 날 때까지 기다린다 — 풀이 닫혔으면 거절 */
    private object BlockingSubmit : RejectedExecutionHandler {
        override fun rejectedExecution(task: Runnable, executor: ThreadPoolExecutor) {
            if (executor.isShutdown) throw RejectedExecutionException("발송 풀 종료")
            try {
                executor.queue.put(task)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RejectedExecutionException("제출 대기 중 중단", e)
            }
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS = 5L
    }

    private fun namedDaemon(prefix: String): ThreadFactory {
        val seq = AtomicInteger()
        return ThreadFactory { r -> Thread(r, prefix + seq.incrementAndGet()).apply { isDaemon = true } }
    }
}

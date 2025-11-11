package com.google.adk.sessions;

// 导入项目中的类
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.SessionException;
import com.google.adk.sessions.SessionNotFoundException;
import io.reactivex.rxjava3.observers.TestObserver;

// 导入 JUnit 5 和 Mockito
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings; // 【修正 A】: 导入 Mockito 设置
import org.mockito.quality.Strictness; // 【修正 A】: 导入严格模式设置
import org.mockito.ArgumentCaptor; // 【修正 B】: 导入参数捕获器

// 导入 Jedis (Redis 客户端)
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

// 导入 Java 标准库
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// 导入 Mockito 和 Truth (用于断言)
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq; // 【修正 B】: 导入 eq
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.google.common.truth.Truth.assertThat; // 【修正 B】: 导入 Google Truth 断言

/**
 * 针对 RedisSessionService 的单元测试。
 * (v4 - 修正了 Mockito 严格模式 和 JSON 比较问题)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 【修正 A】: 告诉 Mockito 对不必要的 Stub 宽容
public class RedisSessionServiceTest {

    // --- 要模拟的依赖 (Mocks) ---
    @Mock
    private JedisPool mockJedisPool;

    @Mock
    private Jedis mockJedis;

    @Mock
    private Transaction mockTransaction;

    // --- 要测试的对象 ---
    @InjectMocks
    private RedisSessionService redisService;

    // --- 辅助变量 ---
    private String testAppName;
    private String testUserId;
    private String testSessionId;
    private String testKey;
    private Session testSession;

    @BeforeEach
    void setUp() {
        // 1. 配置 Mock 的基础行为
        when(mockJedisPool.getResource()).thenReturn(mockJedis);
        // 【修正 A】: lenient() 已通过类注解设置，这里不再需要
        when(mockJedis.multi()).thenReturn(mockTransaction);

        // 2. 设置通用的测试数据
        testAppName = "test-app";
        testUserId = "user-123";
        testSessionId = UUID.randomUUID().toString();
        testKey = "adk:session:test-app:user-123:" + testSessionId;

        // 3. 创建一个基础的 Session 对象用于测试
        testSession = Session.builder(testSessionId)
                .appName(testAppName)
                .userId(testUserId)
                .state(new ConcurrentHashMap<>())
                .events(new ArrayList<>())
                .lastUpdateTime(Instant.now())
                .build();
    }

    // --- 测试 createSession ---

    @Test
    void createSession_shouldSucceedWhenKeyDoesNotExist() {
        when(mockJedis.setnx(anyString(), anyString())).thenReturn(1L);
        TestObserver<Session> testObserver = redisService.createSession(
                testAppName, testUserId, new ConcurrentHashMap<>(), testSessionId).test();
        testObserver.assertNoErrors();
        testObserver.assertValueCount(1);
        verify(mockJedis).setnx(anyString(), any(String.class));
    }

    @Test
    void createSession_shouldFailWhenKeyAlreadyExists() {
        when(mockJedis.setnx(anyString(), anyString())).thenReturn(0L);
        TestObserver<Session> testObserver = redisService.createSession(
                testAppName, testUserId, null, testSessionId).test();
        testObserver.assertError(SessionException.class);
    }

    // --- 测试 getSession ---

    @Test
    void getSession_shouldReturnSessionWhenFound() {
        String sessionJson = testSession.toJson();
        when(mockJedis.get(testKey)).thenReturn(sessionJson);
        TestObserver<Session> testObserver = redisService.getSession(
                testAppName, testUserId, testSessionId, Optional.empty()).test();
        testObserver.assertNoErrors();
        testObserver.assertValue(s -> s.id().equals(testSessionId) && s.userId().equals(testUserId));
    }

    @Test
    void getSession_shouldThrowWhenNotFound() {
        when(mockJedis.get(testKey)).thenReturn(null);
        TestObserver<Session> testObserver = redisService.getSession(
                testAppName, testUserId, testSessionId, Optional.empty()).test();
        testObserver.assertError(SessionNotFoundException.class);
    }

    // --- 测试 appendEvent (核心并发逻辑) ---

    @Test
    void appendEvent_shouldSucceedOnFirstTry() {
        String baseJson = testSession.toJson();
        Event newEvent = Event.builder().id(UUID.randomUUID().toString()).author("user").build();

        when(mockJedis.get(testKey)).thenReturn(baseJson);
        when(mockTransaction.exec()).thenReturn(Collections.singletonList("OK"));

        TestObserver<Event> testObserver = redisService.appendEvent(testSession, newEvent).test();
        testObserver.assertNoErrors();
        testObserver.assertValue(newEvent);
        verify(mockJedis, times(1)).watch(testKey);
    }

    @Test
    void appendEvent_shouldRetryOnConflictAndThenSucceed() {
        String baseJson = testSession.toJson();
        Event newEvent = Event.builder().id(UUID.randomUUID().toString()).author("user").build();

        Session conflictingSession = Session.builder(testSessionId)
                .appName(testAppName).userId(testUserId)
                .events(List.of(Event.builder().id("other-event").build()))
                .build();
        String conflictingJson = conflictingSession.toJson();

        when(mockTransaction.exec())
                .thenReturn(null) // 第一次失败
                .thenReturn(Collections.singletonList("OK")); // 第二次成功

        when(mockJedis.get(testKey))
                .thenReturn(baseJson) // 第一次
                .thenReturn(conflictingJson); // 第二次

        TestObserver<Event> testObserver = redisService.appendEvent(testSession, newEvent).test();
        testObserver.assertNoErrors();
        verify(mockJedis, times(2)).watch(testKey);
        verify(mockTransaction, times(2)).exec();
    }

    @Test
    void appendEvent_shouldFailAfterMaxRetries() {
        String baseJson = testSession.toJson();
        Event newEvent = Event.builder().id(UUID.randomUUID().toString()).author("user").build();

        when(mockJedis.get(testKey)).thenReturn(baseJson);
        when(mockTransaction.exec()).thenReturn(null);

        TestObserver<Event> testObserver = redisService.appendEvent(testSession, newEvent).test();
        testObserver.assertError(ConcurrentModificationException.class);

        int maxRetries = 5;
        verify(mockJedis, times(maxRetries)).watch(testKey);
        verify(mockTransaction, times(maxRetries)).exec();
    }

    @Test
    void appendEvent_shouldUpdateStateFromStateDelta() {
        // 【测试场景】：验证 stateDelta 被正确合并
        // 【修正 B】: 不再比较完整的 JSON 字符串，只验证我们关心的部分

        // 1. 安排 (Arrange)
        String baseJson = testSession.toJson(); // 原始 Session (state 为空)

        ConcurrentMap<String, Object> stateDeltaMap = new ConcurrentHashMap<>();
        stateDeltaMap.put("intent", "booking");
        stateDeltaMap.put("guests", 2);

        Event newEvent = Event.builder()
                .id(UUID.randomUUID().toString())
                .author("agent")
                .actions(EventActions.builder()
                        .stateDelta(stateDeltaMap)
                        .build())
                .build();

        when(mockJedis.get(testKey)).thenReturn(baseJson);
        when(mockTransaction.exec()).thenReturn(Collections.singletonList("OK"));

        // 2. 行动 (Act)
        redisService.appendEvent(testSession, newEvent).blockingGet();

        // 3. 断言 (Assert)
        // 【修正 B】: 使用 ArgumentCaptor 捕获传递给 mockTransaction.set() 的 JSON 字符串
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);

        // 验证 set 被调用，并捕获第二个参数 (JSON string)
        verify(mockTransaction).set(eq(testKey), jsonCaptor.capture());

        String actualJson = jsonCaptor.getValue();

        // 使用 Google Truth 断言，只检查我们关心的部分
        assertThat(actualJson).isNotNull();
        assertThat(actualJson).contains("\"intent\":\"booking\""); // 验证 state.intent
        assertThat(actualJson).contains("\"guests\":2"); // 验证 state.guests
        assertThat(actualJson).contains(newEvent.id()); // 验证 event 被添加
    }
}
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.google.common.truth.Truth.assertThat;

/**
 * 针对 RedisSessionService 的单元测试。
 * (v5 - 修正了 Session.toBuilder() 的错误)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class RedisSessionServiceTest {

    // --- (Mocks, @InjectMocks, 辅助变量... 均无变化) ---
    @Mock
    private JedisPool mockJedisPool;

    @Mock
    private Jedis mockJedis;

    @Mock
    private Transaction mockTransaction;

    @InjectMocks
    private RedisSessionService redisService;

    private String testAppName;
    private String testUserId;
    private String testSessionId;
    private String testKey;
    private Session testSession;

    @BeforeEach
    void setUp() {
        // --- (setUp... 均无变化) ---
        when(mockJedisPool.getResource()).thenReturn(mockJedis);
        when(mockJedis.multi()).thenReturn(mockTransaction);

        testAppName = "test-app";
        testUserId = "user-123";
        testSessionId = UUID.randomUUID().toString();
        testKey = "adk:session:test-app:user-123:" + testSessionId;

        testSession = Session.builder(testSessionId)
                .appName(testAppName)
                .userId(testUserId)
                .state(new ConcurrentHashMap<>())
                .events(new ArrayList<>())
                .lastUpdateTime(Instant.now())
                .build();
    }

    // --- (createSession, getSession, appendEvent... 等 7 个测试均无变化) ---

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
    void getSession_shouldCompleteEmptyWhenNotFound() {
        when(mockJedis.get(testKey)).thenReturn(null);
        TestObserver<Session> testObserver = redisService.getSession(
                testAppName, testUserId, testSessionId, Optional.empty()).test();
        testObserver.assertNoErrors();
        testObserver.assertComplete();
        testObserver.assertNoValues();
    }

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
                .thenReturn(null)
                .thenReturn(Collections.singletonList("OK"));
        when(mockJedis.get(testKey))
                .thenReturn(baseJson)
                .thenReturn(conflictingJson);
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
        String baseJson = testSession.toJson();
        ConcurrentMap<String, Object> stateDeltaMap = new ConcurrentHashMap<>();
        stateDeltaMap.put("intent", "booking");
        stateDeltaMap.put("guests", 2);
        Event newEvent = Event.builder()
                .id(UUID.randomUUID().toString())
                .author("agent")
                .actions(EventActions.builder().stateDelta(stateDeltaMap).build())
                .build();
        when(mockJedis.get(testKey)).thenReturn(baseJson);
        when(mockTransaction.exec()).thenReturn(Collections.singletonList("OK"));

        redisService.appendEvent(testSession, newEvent).blockingGet();

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockTransaction).set(eq(testKey), jsonCaptor.capture());
        String actualJson = jsonCaptor.getValue();

        assertThat(actualJson).isNotNull();
        assertThat(actualJson).contains("\"intent\":\"booking\"");
        assertThat(actualJson).contains("\"guests\":2");
        assertThat(actualJson).contains(newEvent.id());
    }

    // 【新测试】
    @Test
    void appendEvent_shouldRemoveKeyWhenStateDeltaValueIsRemoved() {
        // 1. 安排 (Arrange)

        // 【修正 V5】: Session.java 没有 toBuilder()。我们必须手动复制所有字段。
        Session sessionWithState = Session.builder(testSession.id()) // 1. 传入 ID
                .appName(testSession.appName())               // 2. 复制 appName
                .userId(testSession.userId())                 // 3. 复制 userId
                .events(testSession.events())                 // 4. 复制 events
                .lastUpdateTime(testSession.lastUpdateTime()) // 5. 复制 time
                .state(new ConcurrentHashMap<>(Map.of("intent", "booking", "foo", "bar"))) // 6. 【关键】设置我们想要的 state
                .build();
        String baseJson = sessionWithState.toJson();

        // 新 Event 请求“删除” intent，并“更新” foo
        ConcurrentMap<String, Object> stateDeltaMap = new ConcurrentHashMap<>();
        stateDeltaMap.put("intent", State.REMOVED); // <-- 关键：删除
        stateDeltaMap.put("foo", "baz"); // <-- 关键：更新

        Event newEvent = Event.builder()
                .id(UUID.randomUUID().toString())
                .author("agent")
                .actions(EventActions.builder().stateDelta(stateDeltaMap).build())
                .build();

        when(mockJedis.get(testKey)).thenReturn(baseJson);
        when(mockTransaction.exec()).thenReturn(Collections.singletonList("OK"));

        // 2. 行动 (Act)
        redisService.appendEvent(testSession, newEvent).blockingGet();

        // 3. 断言 (Assert)
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockTransaction).set(eq(testKey), jsonCaptor.capture());
        String actualJson = jsonCaptor.getValue();

        assertThat(actualJson).isNotNull();
        assertThat(actualJson).doesNotContain("\"intent\":\"booking\""); // 验证 "intent" 已被删除
        assertThat(actualJson).contains("\"foo\":\"baz\""); // 验证 "foo" 已被更新
    }
}
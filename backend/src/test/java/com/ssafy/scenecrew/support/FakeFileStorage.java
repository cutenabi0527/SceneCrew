package com.ssafy.scenecrew.support;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.ssafy.scenecrew.global.storage.FileStorage;
import com.ssafy.scenecrew.global.storage.FileStorageException;
import com.ssafy.scenecrew.global.storage.StoredFile;
import com.ssafy.scenecrew.global.storage.StoredFileNotFoundException;

/** 메모리에 저장하는 시험용 저장소. 저장·삭제 실패를 켤 수 있다. 실제 S3 동작은 S3FileStorageTest가 확인한다. */
public class FakeFileStorage implements FileStorage {
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    /** 이 횟수만큼 저장에 성공한 뒤부터 저장이 실패한다. 음수면 실패하지 않는다. */
    private volatile int failPutAfter = -1;
    private volatile boolean failDelete;
    /** 설정되면 저장마다 arrived를 하나 줄이고 release가 열릴 때까지 기다린다. 동시 업로드가 실제로 겹치게 할 때 쓴다. */
    private volatile CountDownLatch arrived;
    private volatile CountDownLatch release;

    public record StoredObject(byte[] content, String contentType) {
    }

    @Override
    public String put(String key, byte[] content, String contentType) {
        CountDownLatch gate = release;
        if (gate != null) {
            arrived.countDown();
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new FileStorageException("저장 대기 시간 초과: " + key);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FileStorageException("저장 대기 중단: " + key);
            }
        }
        if (failPutAfter == 0) {
            throw new FileStorageException("저장 실패 시험: " + key);
        }
        if (failPutAfter > 0) {
            failPutAfter--;
        }
        objects.put(key, new StoredObject(content.clone(), contentType));
        return sha256(content);
    }

    @Override
    public StoredFile get(String key) {
        StoredObject object = objects.get(key);
        if (object == null) {
            throw new StoredFileNotFoundException(key, null);
        }
        return new StoredFile(new ByteArrayInputStream(object.content()), object.contentType(), object.content().length);
    }

    @Override
    public void delete(String key) {
        if (failDelete) {
            throw new FileStorageException("삭제 실패 시험: " + key);
        }
        objects.remove(key);
    }

    public Map<String, StoredObject> objects() {
        return objects;
    }

    public void failPutAfter(int successes) {
        this.failPutAfter = successes;
    }

    public void failDelete(boolean failDelete) {
        this.failDelete = failDelete;
    }

    /** 다음 저장들을 arrived까지 모은 뒤 release가 열릴 때 함께 진행시킨다. reset으로 해제한다. */
    public void holdPuts(CountDownLatch arrived, CountDownLatch release) {
        this.arrived = arrived;
        this.release = release;
    }

    public void reset() {
        CountDownLatch gate = release;
        release = null;
        arrived = null;
        if (gate != null) {
            gate.countDown();
        }
        objects.clear();
        failPutAfter = -1;
        failDelete = false;
    }

    public static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

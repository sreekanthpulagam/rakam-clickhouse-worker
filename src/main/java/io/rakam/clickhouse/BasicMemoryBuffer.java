package io.rakam.clickhouse;

import java.util.ArrayList;
import java.util.List;

public class BasicMemoryBuffer<T>
{

    private final int numMessagesToBuffer;
    private final long millisecondsToBuffer;

    private final List<T> buffer;

    private long previousFlushTimeMillisecond;

    public BasicMemoryBuffer(StreamConfig config)
    {
        numMessagesToBuffer = config.getMaxFlushRecords();
        millisecondsToBuffer = config.getMaxFlushDuration().toMillis();
        previousFlushTimeMillisecond = System.currentTimeMillis();
        this.buffer = new ArrayList<>(numMessagesToBuffer);
    }

    public long getNumRecordsToBuffer()
    {
        return numMessagesToBuffer;
    }

    public long getMillisecondsToBuffer()
    {
        return millisecondsToBuffer;
    }

    public void consumeRecord(T record)
    {
        buffer.add(record);
    }

    public void clear()
    {
        buffer.clear();
        previousFlushTimeMillisecond = System.currentTimeMillis();
    }

    public boolean shouldFlush()
    {
        int size = buffer.size();
        if (size == 0) {
            return false;
        }
        long timelapseMillisecond = System.currentTimeMillis() - previousFlushTimeMillisecond;
        return ((size >= getNumRecordsToBuffer()) || (timelapseMillisecond >= getMillisecondsToBuffer()));
    }

    public List<T> getRecords()
    {
        return buffer;
    }
}

package crux.api;

import clojure.lang.Keyword;

import java.io.Closeable;
import java.time.Duration;
import java.util.*;

import crux.api.tx.*;
import crux.api.*;

import static org.junit.Assert.*;

public class TestUtils {
    static final Keyword PUT = Keyword.intern("crux.tx/put");
    static final Keyword DELETE = Keyword.intern("crux.tx/delete");

    static final Keyword DB_ID = Keyword.intern("crux.db/id");
    static final Keyword VALID_TIME = Keyword.intern("crux.db/valid-time");
    static final Keyword CONTENT_HASH = Keyword.intern("crux.db/content-hash");
    static final Keyword DOC = Keyword.intern("crux.db/doc");

    static final Keyword TX_TIME = Keyword.intern("crux.tx/tx-time");
    static final Keyword TX_ID = Keyword.intern("crux.tx/tx-id");
    static final Keyword TX_EVENTS = Keyword.intern("crux.tx.event/tx-events");
    static final Keyword TX = Keyword.intern("crux.tx/tx");

    static final Keyword TX_OPS = Keyword.intern("crux.api/tx-ops");

    static final Duration duration = Duration.ofSeconds(10);

    static final Date now = new Date();

    static final String documentId = "myDoc";
    static final String versionId = "version";

    static CruxDocument testDocument(long version) {
        CruxDocument document = CruxDocument.create(documentId);
        return document.plus(versionId, version);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void sync(ICruxAPI node) {
        node.sync(duration);
    }

    static List<Object> listOf(Object... objects) {
        return Arrays.asList(objects);
    }

    public static Date date(long diff) {
        return Date.from(now.toInstant().plusMillis(diff));
    }

    static long invertDate(Date date) {
        return now.getTime() - date.getTime();
    }

    static TransactionInstant tx(ICruxAPI node, Transaction transaction) {
        return node.submitTx(transaction);
    }

    static TransactionInstant tx(ICruxAPI node, TransactionOperation transactionOperation) {
        Transaction transaction = Transaction.buildTx(tx -> {
           tx.add(transactionOperation);
        });
        return tx(node, transaction);
    }

    static TransactionInstant put(ICruxAPI node, CruxDocument document, Date validTime, Date endValidTime) {
        TransactionOperation txOp;
        if (endValidTime != null) {
            txOp = PutOperation.create(document, validTime, endValidTime);
        }
        else if (validTime != null) {
            txOp = PutOperation.create(document, validTime);
        }
        else {
            txOp = PutOperation.create(document);
        }

        return tx(node, txOp);
    }

    static TransactionInstant delete(ICruxAPI node, Object documentId, Date validTime, Date endValidTime) {
        TransactionOperation txOp;
        if (endValidTime != null) {
            txOp = DeleteOperation.create(documentId, validTime, endValidTime);
        }
        else if (validTime != null) {
            txOp = DeleteOperation.create(documentId, validTime);
        }
        else {
            txOp = DeleteOperation.create(documentId);
        }

        return tx(node, txOp);
    }

    public static void close(Closeable closeable) {
        try {
            closeable.close();
        }
        catch (Exception e) {
            fail();
        }
    }

    static void assertHasKeys(List<Map<Keyword, ?>> maps, Keyword... keys) {
        for (Map<Keyword, ?> map: maps) {
            assertHasKeys(map, keys);
        }
    }

    static void assertHasKeys(Map<Keyword, ?> map, Keyword... keys) {
        for (Keyword key: keys) {
            assertTrue(map.containsKey(key));
        }

        assertEquals(keys.length, map.size());
    }

    static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    public static void awaitTx(ICruxAPI node, TransactionInstant tx) {
        node.awaitTx(tx, duration);
    }

    static Object getTransaction(Map<Keyword, ?> logEntry) {
        return logEntry.get(TX_OPS);
    }

    static TransactionInstant getTransactionInstant(Map<Keyword, ?> logEntry) {
        return TransactionInstant.factory(logEntry);
    }
}

package com.tctest;

import java.util.Iterator;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Hit;
import org.apache.lucene.search.Hits;

import com.tc.object.config.ConfigVisitor;
import com.tc.object.config.DSOClientConfigHelper;
import com.tc.object.config.TransparencyClassSpec;
import com.tc.simulator.app.ApplicationConfig;
import com.tc.simulator.listener.ListenerProvider;
import com.tctest.runner.AbstractErrorCatchingTransparentApp;

public class RAMDirectoryTestApp extends AbstractErrorCatchingTransparentApp {

	static final int EXPECTED_THREAD_COUNT = 2;

	private final CyclicBarrier barrier;

	private final RAMDirectory clusteredDirectory;

	private final StandardAnalyzer analyzer;

	public static void visitL1DSOConfig(final ConfigVisitor visitor,
			final DSOClientConfigHelper config) {
		config.addNewModule("clustered-lucene", "2.0.0");

		final String testClass = RAMDirectoryTestApp.class.getName();
		config.addIncludePattern(testClass + "$*");

		final TransparencyClassSpec spec = config.getOrCreateSpec(testClass);
		final java.lang.reflect.Field[] fields = RAMDirectoryTestApp.class
				.getDeclaredFields();
		for (int pos = 0; pos < fields.length; ++pos) {
			final Class fieldType = fields[pos].getType();
			if (fieldType == CyclicBarrier.class
					|| fieldType == RAMDirectory.class) {
				spec.addRoot(fields[pos].getName(), fields[pos].getName());
			}
		}
	}

	public RAMDirectoryTestApp(final String appId, final ApplicationConfig cfg,
			final ListenerProvider listenerProvider) {
		super(appId, cfg, listenerProvider);
		barrier = new CyclicBarrier(getParticipantCount());
		clusteredDirectory = new RAMDirectory();
		analyzer = new StandardAnalyzer();
	}

	protected void runTest() throws Throwable {
		if (barrier.await() == 0) {
			addDataToMap(2);
			letOtherNodeProceed();
			waitForPermissionToProceed();
			verifyEntries(4);
			removeDataFromMap(2);
			letOtherNodeProceed();
			waitForPermissionToProceed();
			verifyEntries(0);
		} else {
			waitForPermissionToProceed();
			verifyEntries(2);
			addDataToMap(2);
			letOtherNodeProceed();
			waitForPermissionToProceed();
			verifyEntries(2);
			// clusteredFastHashMap.clear();
			letOtherNodeProceed();
		}
		barrier.await();
	}

	// This is lame but it makes runTest() slightly more readable
	private void letOtherNodeProceed() throws InterruptedException,
			BrokenBarrierException {
		barrier.await();
	}

	// This is lame but it makes runTest() slightly more readable
	private void waitForPermissionToProceed() throws InterruptedException,
			BrokenBarrierException {
		barrier.await();
	}

	private void addDataToMap(final int count) {
		for (int pos = 0; pos < count; ++pos) {
			// clusteredFastHashMap.put(new Object(), new Object());
		}
	}

	private void removeDataFromMap(final int count) {
		// for (int pos = 0; pos < count; ++pos) {
		// clusteredFastHashMap.remove(clusteredFastHashMap.keySet()
		// .iterator().next());
		// }
	}

	private void verifyEntries(final int count) {
		// Assert.assertEquals(count, clusteredFastHashMap.size());
		// Assert.assertEquals(count, clusteredFastHashMap.keySet().size());
		// Assert.assertEquals(count, clusteredFastHashMap.values().size());
	}

	private void put(final String key, final String value) throws Exception {
		final Document doc = new Document();
		doc.add(new Field("key", key, Field.Store.YES, Field.Index.TOKENIZED));
		doc.add(new Field("value", value, Field.Store.YES,
				Field.Index.TOKENIZED));

		synchronized (clusteredDirectory) {
			final IndexWriter writer = new IndexWriter(this.clusteredDirectory,
					this.analyzer, this.clusteredDirectory.list().length == 0);
			writer.addDocument(doc);
			writer.optimize();
			writer.close();
		}
	}

	private String get(final String key) throws Exception {
		final StringBuffer rv = new StringBuffer();
		synchronized (clusteredDirectory) {
			final QueryParser parser = new QueryParser("key", this.analyzer);
			final Query query = parser.parse(key);
			BooleanQuery.setMaxClauseCount(100000);
			final IndexSearcher is = new IndexSearcher(this.clusteredDirectory);
			final Hits hits = is.search(query);

			for (Iterator i = hits.iterator(); i.hasNext();) {
				final Hit hit = (Hit) i.next();
				final Document document = hit.getDocument();
				rv.append(document.get("key") + "=" + document.get("value")
						+ System.getProperty("line.separator"));
			}
		}
		return rv.toString();
	}
}

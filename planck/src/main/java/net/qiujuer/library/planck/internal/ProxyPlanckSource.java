package net.qiujuer.library.planck.internal;

import android.webkit.URLUtil;

import net.qiujuer.library.planck.Planck;
import net.qiujuer.library.planck.PlanckSource;
import net.qiujuer.library.planck.data.DataInfo;
import net.qiujuer.library.planck.data.DataProvider;
import net.qiujuer.library.planck.exception.NetworkException;
import net.qiujuer.library.planck.internal.contract.Initializer;
import net.qiujuer.library.planck.internal.contract.UsageFinalizer;
import net.qiujuer.library.planck.internal.section.CacheDataPartial;
import net.qiujuer.library.planck.internal.section.DataPartial;
import net.qiujuer.library.planck.internal.section.TempDataPartial;
import net.qiujuer.library.planck.utils.CacheUtil;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author qiujuer Email: qiujuer@live.cn
 * @version 1.0.0
 * Create at: 2018/8/11
 */
public class ProxyPlanckSource implements PlanckSource, UsageFinalizer {
    private final AtomicInteger mUsageCount = new AtomicInteger();
    private final AtomicBoolean mDone = new AtomicBoolean();
    private final Planck.Store mPlanckStore;

    private final Object mSourceLock = new Object();
    private final String mSourceUrl;
    private PlanckSource mSource;


    public ProxyPlanckSource(Planck.Store store, String sourceUrl) {
        mPlanckStore = store;
        mSourceUrl = sourceUrl;
    }

    @Override
    public long length(int timeout) throws TimeoutException {
        checkCanceled();
        return awaitSourceInitialed() ? mSource.length(timeout) : PlanckSource.INVALID_VALUES_INIT_INTERRUPTED;
    }

    @Override
    public long load(long position, int timeout) throws IOException, TimeoutException {
        checkCanceled();
        return awaitSourceInitialed() ? mSource.load(position, timeout) : PlanckSource.INVALID_VALUES_INIT_INTERRUPTED;
    }

    @Override
    public int get(long position, byte[] buffer, int offset, int size, int timeout) throws IOException, TimeoutException {
        checkCanceled();
        return awaitSourceInitialed() ? mSource.get(position, buffer, offset, size, timeout) : PlanckSource.INVALID_VALUES_INIT_INTERRUPTED;
    }

    @Override
    public void close() {
        if (onceFinalize()) {
            mDone.set(true);
            if (mSource != null) {
                synchronized (mSourceLock) {
                    if (mSource != null) {
                        mPlanckStore.outOfSource(mSourceUrl);
                        mSource.close();
                        mSource = null;
                    }
                }
            }
        }
    }

    @Override
    public int onceUsage() {
        return mUsageCount.incrementAndGet();
    }

    @Override
    public boolean onceFinalize() {
        return mUsageCount.decrementAndGet() <= 0;
    }

    private void checkCanceled() {
        if (mDone.get()) {
            throw new IllegalStateException("Current source is closed.");
        }
    }

    private boolean awaitSourceInitialed() {
        if (mSource == null) {
            synchronized (mSourceLock) {
                if (mSource == null) {
                    try {
                        mSourceLock.wait();
                    } catch (InterruptedException e) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void attachSource(PlanckSource source) {
        synchronized (mSourceLock) {
            mSource = source;
            try {
                mSourceLock.notifyAll();
            } catch (Exception ignored) {
            }
        }
    }

    private void attachWithLocalSource(String filePath) {
        LocalPlanckSource localPlanckSource = new LocalPlanckSource(new File(filePath));
        attachSource(localPlanckSource);
    }

    private boolean attachWithNetwork(final String httpUrl, final String fileNamePrefix) {
        final long totalSize;
        final long partialSize;
        final boolean acceptRange;
        DataInfo dataInfo = mPlanckStore.dataProvider().loadDataInfo(httpUrl);
        if (dataInfo == null) {
            // TODO
            throw new NetworkException("Load data info error", NetworkException.CUSTOM_ERROR_GET_DATA_INFO);
        } else {
            totalSize = dataInfo.getLength();
            acceptRange = dataInfo.isSupportAcceptRangesOperation();
            partialSize = acceptRange ? mPlanckStore.maxPartialSize(totalSize) : totalSize;
        }

        attachWithPartialCacheOrTemp(httpUrl, fileNamePrefix,
                totalSize, partialSize, acceptRange, mPlanckStore.dataProvider());
        return true;
    }

    private void attachWithPartialCacheOrTemp(final String httpUrl, final String fileNamePrefix,
                                              final long totalSize, final long partialSize,
                                              final boolean acceptRange,
                                              final DataProvider dataProvider) {
        // The number of files is equal to the number of partial files that are all loaded
        final File cacheRoot = mPlanckStore.cacheRoot();

        final int fileCount = (int) ((totalSize + partialSize - 1) / partialSize);

        DataPartial[] dataPartials = new DataPartial[fileCount];

        for (int i = 0; i < fileCount; i++) {
            final long partStartPos = partialSize * i;
            final long partSize = Math.min(partialSize, totalSize - partialSize * i);

            final String fileName = CacheUtil.generateName(fileNamePrefix, i, partStartPos, partSize, totalSize, acceptRange);
            File file = CacheUtil.generateCacheFile(cacheRoot, fileName);

            DataPartial partial;
            if (file.exists()) {
                partial = new CacheDataPartial(file);
            } else {
                File tempFile = CacheUtil.generateTempFile(cacheRoot, fileName);
                partial = new TempDataPartial(tempFile, httpUrl, dataProvider);
            }
            dataPartials[i] = partial;
        }

        PartialPlanckSource partialPlanckSource = new PartialPlanckSource(dataPartials, totalSize, partialSize);
        attachSource(partialPlanckSource);
    }

    public Initializer initializer() {
        return mInitializer;
    }

    private Initializer mInitializer = new Initializer() {
        @Override
        protected boolean onInitialize() {
            if (mDone.get()) {
                return true;
            }

            final String sourceUrl = mSourceUrl;

            // Check and load local file source
            final boolean isLocalFilePath = !URLUtil.isNetworkUrl(sourceUrl);
            if (isLocalFilePath) {
                attachWithLocalSource(sourceUrl);
                return true;
            }

            final File cacheRoot = mPlanckStore.cacheRoot();
            final String fileNamePrefix = mPlanckStore.fileNameGenerator().generate(sourceUrl);
            final File[] childFiles = cacheRoot.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File file, String s) {
                    return s.startsWith(fileNamePrefix);
                }
            });

            // Check and load cache complete file source
            if (childFiles.length == 1) {
                File childFile = childFiles[0];
                if (childFile.exists()) {
                    String childFileName = CacheUtil.removeNameExtension(childFile.getName());
                    if (childFileName.equalsIgnoreCase(fileNamePrefix)) {
                        attachWithLocalSource(sourceUrl);
                        return true;
                    }
                }
            }

            // Check and load network source
            if (childFiles.length == 0) {
                return attachWithNetwork(sourceUrl, fileNamePrefix);
            }

            // Check and load partial cache source
            final int fileNamePrefixIndex = fileNamePrefix.length();
            final String firstChildFileNameMask = "-0-0-";
            File firstChildFile = null;
            for (File childFile : childFiles) {
                if (childFile.getName().startsWith(firstChildFileNameMask, fileNamePrefixIndex)) {
                    firstChildFile = childFile;
                    break;
                }
            }

            // Find first file
            if (firstChildFile == null) {
                // Delete all cache
                for (File childFile : childFiles) {
                    //noinspection ResultOfMethodCallIgnored
                    childFile.delete();
                }
                // Reload with network
                return attachWithNetwork(sourceUrl, fileNamePrefix);
            }


            // Load globe CacheInfo from first file
            CacheUtil.CacheInfo cacheInfo = CacheUtil.getCacheInfo(firstChildFile);
            final long totalSize = cacheInfo.mTotalSize;
            final long partialSize = cacheInfo.mSize;
            final boolean acceptRange = cacheInfo.mAcceptRange;
            attachWithPartialCacheOrTemp(sourceUrl, fileNamePrefix, totalSize, partialSize, acceptRange, mPlanckStore.dataProvider());
            return true;
        }
    };
}

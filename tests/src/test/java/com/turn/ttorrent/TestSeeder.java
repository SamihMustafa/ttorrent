package com.turn.ttorrent;

import com.turn.ttorrent.client.*;
import com.turn.ttorrent.client.storage.FairPieceStorageFactory;
import com.turn.ttorrent.client.storage.FileCollectionStorage;
import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentSerializer;
import com.turn.ttorrent.common.creation.MetadataBuilder;
import com.turn.ttorrent.tracker.Tracker;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertTrue;

@Test
public class TestSeeder {

    @Test
    public void test(){

        File file = new File("/home/samih/Downloads/containerFolder/english.h5p");
        File torrentDir = new File("/home/samih/Downloads/torrentDir/");
        File torrentFile = new File(torrentDir, "test123.torrent");
        File containerFolder = new File("/home/samih/Downloads/containerFolder");

        File downloadFolder = new File("/home/samih/Downloads/torrentDownloads");
        downloadFolder.mkdirs();


        TorrentMetadata metadata;
        final ExecutorService workerES = Executors.newFixedThreadPool(10);
        final ExecutorService validatorES = Executors.newFixedThreadPool(4);

        final ExecutorService workerLE = Executors.newFixedThreadPool(10);
        final ExecutorService validatorLE = Executors.newFixedThreadPool(4);

        SimpleClient client = new SimpleClient();


        CommunicationManager seeder = new CommunicationManager(workerES, validatorES) {
            @Override
            public void stop() {
                super.stop();
                workerES.shutdown();
                validatorES.shutdown();
            }
        };

        CommunicationManager leecher = new CommunicationManager(workerLE, validatorLE) {
            @Override
            public void stop() {
                super.stop();
                workerLE.shutdown();
                validatorLE.shutdown();
            }
        };
        try {

            InetAddress address = InetAddress.getLocalHost();
            seeder.start(address);

            metadata = new MetadataBuilder()
                    .addFile(file)
                   //.addTracker()
                    .setCreatedBy("UstadMobile")
                    .build();

            saveTorrent(metadata, torrentFile);

            FileMetadataProvider metadataProvider = new FileMetadataProvider(torrentFile.getAbsolutePath());
            FileCollectionStorage fileCollectionStorage = FileCollectionStorage.create(metadata, containerFolder);
            PieceStorage pieceStorage = FairPieceStorageFactory.INSTANCE.createStorage(metadata, fileCollectionStorage);

            seeder.addTorrent(metadataProvider, pieceStorage);

            //client.downloadTorrent(torrentFile.getAbsolutePath(), downloadFolder.getAbsolutePath(), address);

            leecher.start(address);
            TorrentManager manager = leecher.addTorrent(torrentFile.getAbsolutePath(), downloadFolder.getAbsolutePath());
            manager.awaitDownloadComplete(10, TimeUnit.MINUTES);


            //final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(loadedTorrent.getTorrentHash().getHexInfoHash());

            System.out.println(seeder.isSeed(metadata.getHexInfoHash()));
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            seeder.stop();
            client.stop();
        }

    }

    private void waitDownloadComplete(TorrentManager torrentManager, int timeoutSec) throws InterruptedException {
        final Semaphore semaphore = new Semaphore(0);
        TorrentListenerWrapper listener = new TorrentListenerWrapper() {
            @Override
            public void downloadComplete() {
                semaphore.release();
            }
        };
        try {
            torrentManager.addListener(listener);
            boolean res = semaphore.tryAcquire(timeoutSec, TimeUnit.SECONDS);
            if (!res) throw new RuntimeException("Unable to download file in " + timeoutSec + " seconds");
        } finally {
            torrentManager.removeListener(listener);
        }
    }

    private void waitForSeederIsAnnounsedOnTracker(final Tracker tracker, final String hexInfoHash) {
        final WaitFor waitFor = new WaitFor(10 * 1000) {
            @Override
            protected boolean condition() {
                return tracker.getTrackedTorrent(hexInfoHash) != null;
            }
        };
        assertTrue(waitFor.isMyResult());
    }

    private void saveTorrent(TorrentMetadata torrent, File file) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(new TorrentSerializer().serialize(torrent));
        fos.close();
    }

}

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.math.*;
import java.util.*;
import java.util.Arrays;
import java.util.Timer;

import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;

public class VideoProcessor {

    final int width = 320;
    final int height = 180;
    final int fps = 30;
    final int totalFrame = 2700;
    private final int threshold = 25;
    private int sec_buffer = 1;

    private int[][][] histogramPrev = new int[4][4][4];
    private int[][][] histogramNext = new int[4][4][4];

    ArrayList<byte[]> soundBlock;
    ArrayList<Double> audioWs = new ArrayList<>();
    ArrayList<Integer> breaksIndex  = new ArrayList<>();
    
    ArrayList<byte[]> soundList = new ArrayList<>();
    ArrayList<Integer> frameList = new ArrayList<>();
    
    public ArrayList<byte[]> getSoundList(){
    	return soundList;
    }
    
    public ArrayList<Integer> getFrameList(){
    	return frameList;
    }

    public VideoProcessor(String pathToWav, String pathToFrames) throws IOException {
        FileInputStream wavInput = getWavInput(pathToWav);
        PlaySound ps = new PlaySound(wavInput);
        this.soundBlock = ps.getSoundArray();
        ArrayList<LogicalShot> shotList = BreakInShots(pathToFrames);
        System.out.println("Audio analysis starting...");
        AudioProcessor ap;
        try {
            ap = new AudioProcessor(pathToWav, breaksIndex);
            audioWs = ap.getAudioWeights();
            System.out.println("Audio weights size: " + audioWs.size());
        } catch (UnsupportedAudioFileException e) {
            e.printStackTrace();
        } catch (PlayWaveException e) {
            e.printStackTrace();
        }
        
        //add audio weights to logical shot score
        for(int i = 0; i < shotList.size(); i++) {
            shotList.get(i).setAudioScore(audioWs.get(i));
            shotList.get(i).calculateTotalScore();
        }
        
        try {
                outputFrameInfo(pathToFrames);
            }
        catch (Exception e) {}

        BufferedWriter writer = new BufferedWriter(new FileWriter("Summary.txt"));
        //soundList = new ArrayList<>();
        frameList = outputFrameSummary(shotList);
        for (int i: frameList)
            soundList.add(soundBlock.get(i));
        int j = 0;
        for (int i: frameList)
            writer.write((j++) + ": " + i + "\n");
        writer.close();
    }

    public ArrayList<Integer> outputFrameSummary(ArrayList<LogicalShot> shotList) {
        ArrayList<Integer> res = new ArrayList<>();
        int remainingFrames = totalFrame;
        Collections.sort(shotList, new Comparator<LogicalShot>() {
            @Override
            public int compare(LogicalShot s1, LogicalShot s2) {
                if (s1.getScore() == s2.getScore())
                    return 0;
                else if (s1.getScore() > s2.getScore())
                    return 1;
                return -1;
            }
        });
        for (LogicalShot shot: shotList) {
            if (shot.getDuraion() < remainingFrames) {
                shot.setFramesToKeep(shot.getDuraion());
                remainingFrames -= shot.getDuraion();
            }
            else {
                shot.setFramesToKeep(remainingFrames);
                break;
            }
        }

        Collections.sort(shotList, new Comparator<LogicalShot>() {
            @Override
            public int compare(LogicalShot s1, LogicalShot s2) {
                if (s1.getShotId() == s2.getShotId())
                    return 0;
                else if (s1.getShotId() > s2.getShotId())
                    return 1;
                return -1;
            }
        });
        for (LogicalShot shot: shotList) {
            for (int i = 0; i < shot.getFramesToKeep(); i++) 
                res.add(shot.getStartFrameId() + i);
        }

        return res;
    }

    private void outputFrameInfo(String pathToFrames) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter("FrameInfo.txt"));
        for (int i = 1; i < 30 * 60 * 9; i++) {
            Frame temp = new Frame(pathToFrames, i);
            writer.write(temp.toString() + "\n");

        }
        writer.close();
    }

    private static FileInputStream getWavInput(String pathToWav) {
        FileInputStream wavInput = null;
        try {
            wavInput = new FileInputStream(pathToWav);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        return wavInput;
    }

    public ArrayList<LogicalShot> BreakInShots(String pathToFrames) {
        ArrayList<LogicalShot> shotList = new ArrayList<>();
        ReadBytes(pathToFrames, histogramPrev, 0);
        // shots.add(0);
        int frameNumber = 2;
        int frameCounter = 0;
        int pastBreak = fps * sec_buffer;

        int shotId = 0;
        int boundary = 0;
        for (int i = 1; i < 30 * 60 * 9; i++) {
            if (frameCounter == frameNumber) {
                ClearHistogramNext();
                ReadBytes(pathToFrames, histogramNext, i);
                double val = SDvalue();
                val = val / (width * height);
                val *= 100;
                // if SDValue is greater than threshold
                // add frame index, which is the start of new shot to the ds
                if (val > threshold && pastBreak <= 0 && val < 200) {
                    ArrayList<Frame> frameList = new ArrayList<>();
                    for (int j = boundary; j < i; j++) {
                        Frame temp = new Frame(pathToFrames, j);
                        frameList.add(temp);
                    }
                    shotList.add(new LogicalShot(shotId, frameList));
                    shotId++;
                    boundary = i;
                    pastBreak = fps * sec_buffer;
                    System.out.println("finished processing shot " + shotId);
                    breaksIndex.add(i);
                }
                CopyHistogramBack();
                frameCounter = 0;
            } else {
                // do nothing, skip frames here
            }
            frameCounter++;
            pastBreak--;
        }

        if (boundary < 30 * 60 * 9 - 1) {
            ArrayList<Frame> frameList = new ArrayList<>();
            for (int j = boundary; j < 30 * 60 * 9; j++) {
                frameList.add(new Frame(pathToFrames, j));
            }
            shotList.add(new LogicalShot(shotId, frameList));
            breaksIndex.add(boundary);
            System.out.println("finished processing shot " + shotId);
            ;
        }
        System.out.println("Number of shots: " + shotList.size());
        return shotList;
    }

    public void ReadBytes(String pathToFrames, int[][][] histogram, int i) {
        try {
            int frameLength = width * height * 3;
            String imgPath = pathToFrames + "frame" + i + ".rgb";
            File file = new File(imgPath);
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            raf.seek(0);

            long len = frameLength;
            byte[] bytes = new byte[(int) len];

            raf.read(bytes);

            int ind = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    byte r = bytes[ind];
                    byte g = bytes[ind + height * width];
                    byte b = bytes[ind + height * width * 2];
                    // extracts the two most siginificant bits
                    int ri = (int) ((r & 0xff) & 0xC0);
                    int gi = (int) ((g & 0xff) & 0xC0);
                    int bi = (int) ((b & 0xff) & 0xC0);

                    histogram[ri / 64][gi / 64][bi / 64]++;
                    ind++;
                }
            }
            raf.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void CopyHistogramBack() {
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int k = 0; k < 4; k++) {
                    histogramPrev[i][j][k] = histogramNext[i][j][k];
                }
    }

    private void ClearHistogramNext() {
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int k = 0; k < 4; k++) {
                    histogramNext[i][j][k] = 0;
                }
    }

    // measures change of color intensity between two frames
    private double SDvalue() {
        int sum = 0;
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int k = 0; k < 4; k++) {
                    sum += Math.abs(histogramPrev[i][j][k] - histogramNext[i][j][k]);
                }
        return ((double) sum);
    }
}

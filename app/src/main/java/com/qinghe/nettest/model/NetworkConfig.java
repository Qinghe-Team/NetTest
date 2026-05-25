package com.qinghe.nettest.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "network_configs")
public class NetworkConfig {

    @PrimaryKey(autoGenerate = true)
    private int id = 0;
    private String name = "";
    private int upDelay = 0;
    private int upJitter = 0;
    private int upBandwidth = 0;
    private int upPacketLoss = 0;
    private int upContinuousLossPassTime = 0;
    private int upContinuousLossDropTime = 0;
    private int downDelay = 0;
    private int downJitter = 0;
    private int downBandwidth = 0;
    private int downPacketLoss = 0;
    private int downContinuousLossPassTime = 0;
    private int downContinuousLossDropTime = 0;
    private int protocol = 3;

    public NetworkConfig() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getUpDelay() {
        return upDelay;
    }

    public void setUpDelay(int upDelay) {
        this.upDelay = upDelay;
    }

    public int getUpJitter() {
        return upJitter;
    }

    public void setUpJitter(int upJitter) {
        this.upJitter = upJitter;
    }

    public int getUpBandwidth() {
        return upBandwidth;
    }

    public void setUpBandwidth(int upBandwidth) {
        this.upBandwidth = upBandwidth;
    }

    public int getUpPacketLoss() {
        return upPacketLoss;
    }

    public void setUpPacketLoss(int upPacketLoss) {
        this.upPacketLoss = upPacketLoss;
    }

    public int getUpContinuousLossPassTime() {
        return upContinuousLossPassTime;
    }

    public void setUpContinuousLossPassTime(int upContinuousLossPassTime) {
        this.upContinuousLossPassTime = upContinuousLossPassTime;
    }

    public int getUpContinuousLossDropTime() {
        return upContinuousLossDropTime;
    }

    public void setUpContinuousLossDropTime(int upContinuousLossDropTime) {
        this.upContinuousLossDropTime = upContinuousLossDropTime;
    }

    public int getDownDelay() {
        return downDelay;
    }

    public void setDownDelay(int downDelay) {
        this.downDelay = downDelay;
    }

    public int getDownJitter() {
        return downJitter;
    }

    public void setDownJitter(int downJitter) {
        this.downJitter = downJitter;
    }

    public int getDownBandwidth() {
        return downBandwidth;
    }

    public void setDownBandwidth(int downBandwidth) {
        this.downBandwidth = downBandwidth;
    }

    public int getDownPacketLoss() {
        return downPacketLoss;
    }

    public void setDownPacketLoss(int downPacketLoss) {
        this.downPacketLoss = downPacketLoss;
    }

    public int getDownContinuousLossPassTime() {
        return downContinuousLossPassTime;
    }

    public void setDownContinuousLossPassTime(int downContinuousLossPassTime) {
        this.downContinuousLossPassTime = downContinuousLossPassTime;
    }

    public int getDownContinuousLossDropTime() {
        return downContinuousLossDropTime;
    }

    public void setDownContinuousLossDropTime(int downContinuousLossDropTime) {
        this.downContinuousLossDropTime = downContinuousLossDropTime;
    }

    public int getProtocol() {
        return protocol;
    }

    public void setProtocol(int protocol) {
        this.protocol = protocol;
    }
}

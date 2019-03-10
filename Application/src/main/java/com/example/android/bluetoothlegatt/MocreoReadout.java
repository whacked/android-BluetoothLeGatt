package com.example.android.bluetoothlegatt;

import java.util.HashMap;
import java.util.Map;

/*
  |------+----------+-------------------------|
  | g    |        0 | AC-05-00-00-00-00-CE-CE |
  | g    |      257 | AC-05-00-01-01-00-CA-CE |
  |------+----------+-------------------------|
  | ml   |        0 | AC-05-00-00-00-10-CE-DE |
  | ml   |      257 | AC-05-00-01-01-10-CE-DE |
  |------+----------+-------------------------|
  | lb   |        0 | AC-05-00-00-00-24-CE-F2 |
  | lb   |     9.05 | AC-05-00-03-89-24-CA-7A |
  | lb   |     10.8 |       00 00 6C 22 CA 58 |
  | lb   |   1:1.13 |       00 06 C2 24 CA B6 |
  |------+----------+-------------------------|
  | oz   |        0 | AC-05-00-00-00-34-CE-02 |
  | oz   |      905 | AC-05-00-03-89-34-CA-8A |
  |------+----------+-------------------------|

  AC 05 00 03 89 34 CA 8A
           |      `- (16) unit
            `--'- (9 ~ 14) value
*/

enum Unit
{
    G, ML, LB, OZ
}

public class MocreoReadout {
    static class InvalidReadoutException extends Exception {
        public InvalidReadoutException(String message) {
            super(message);
        }
    }

    public static Map<Character, Unit> unitMapping;
    static {
        Map<Character, Unit> initMap = new HashMap<>();
        initMap.put('0', Unit.G);
        initMap.put('1', Unit.ML);
        initMap.put('2', Unit.LB);
        initMap.put('3', Unit.OZ);
        unitMapping = initMap;
    }

    public final Unit unit;
    public final double value;

    public MocreoReadout(Unit measurementUnit, double measuredValue) {
        unit = measurementUnit;
        value = measuredValue;
    }

    public String toString() {
        String out = "";
        switch(unit) {
            case G:
                out = String.format("%dg", (int) value);
                break;
            case ML:
                out = String.format("%dml", (int) value);
                break;
            case OZ:
                out = String.format("%.2foz", value);
                break;
            case LB:
                if(value < 16) {
                    if(value > 10) {
                        out = String.format("%.1f oz", value);
                    } else {
                        out = String.format("%.2f oz", value);
                    }
                } else {
                    int lb = (int) Math.floor(value) / 16;
                    double oz = value % 16.0;
                    out = String.format("%d lb %.2f oz", lb, oz);
                }
                break;
        }
        return out;
    }

    public static MocreoReadout parseReadoutData(String maybeReadoutData) throws InvalidReadoutException {
        if(maybeReadoutData.length() < 32) {
            throw new InvalidReadoutException(
                    String.format("incorrect length (expected >= %d, received %d)",
                            32, maybeReadoutData.length()));
        }
        String dataSection = maybeReadoutData.split("\n")[1];
        if(dataSection.length() < 24) {
            throw new InvalidReadoutException(
                    String.format("data segment incomplete: %s",
                            dataSection));
        }
        Character maybeUnit = dataSection.charAt(15);
        if(!unitMapping.containsKey(maybeUnit)) {
            throw new InvalidReadoutException(
                    String.format("unknown unit character %s",
                            maybeUnit.toString()));
        }

        Unit unit = unitMapping.get(maybeUnit);
        Integer intValue;
        if(dataSection.charAt(7) != '0') {
            intValue = 0;
        } else {
            String valueSection = dataSection.substring(9, 14).replaceAll(
                    " ", "");
            intValue = Integer.parseInt(valueSection, 16);
        }
        Double value = intValue.doubleValue();
        if(unit == Unit.OZ) {
            value /= 100.0;
        } else if(unit == Unit.LB) {
            if(dataSection.charAt(16) == '2') { // active when 10 <= oz < 16
                value /= 10.0;
            } else {
                value /= 100.0;
            }
        }
        return new MocreoReadout(unit, value);
    }
}

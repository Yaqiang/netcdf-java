/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.ft.fmrc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.stream.Collectors;
import ucar.nc2.Attribute;
import ucar.nc2.constants.CF;
import ucar.nc2.dataset.CoordinateAxis1DTime;
import ucar.nc2.time.Calendar;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarDateUnit;
import ucar.nc2.units.DateUnit;
import ucar.nc2.util.Misc;

/**
 * Represents a list of offset times shared among variables
 * Tracks a list of variables that all have the same list of offset times.
 */
public class TimeCoord implements Comparable<TimeCoord> {
  static public final TimeCoord EMPTY = new TimeCoord(CalendarDate.of(new Date()), new double[0]);

  private CalendarDate runDate;
  private List<GridDatasetInv.Grid> gridInv; // track the grids that use this coord
  private int id; // unique id for serialization
  private String axisName; // time coordinate axis

  // time at point has offsets, intervals have bounds
  private boolean isInterval = false;
  private double[] offset; // hours since runDate
  private double[] bound1, bound2; // hours since runDate [ntimes,2]

  TimeCoord(CalendarDate runDate) {
    this.runDate = runDate;
  }

  TimeCoord(CalendarDate runDate, double[] offset) {
    this.runDate = runDate;
    this.offset = offset;
  }

  TimeCoord(TimeCoord from) {
    this.runDate = from.runDate;
    this.axisName = from.axisName;
    this.offset = from.offset;
    this.isInterval = from.isInterval;
    this.bound1 = from.bound1;
    this.bound2 = from.bound2;
    this.id = from.id;
  }

  TimeCoord(CalendarDate runDate, CoordinateAxis1DTime axis) {
    this.runDate = runDate;
    this.axisName = axis.getFullName();

    DateUnit unit;
    Attribute atrCal;
    Calendar cal;
    
    try {
      unit = new DateUnit(axis.getUnitsString());
      atrCal = axis.findAttribute(CF.CALENDAR );
      if(atrCal != null)
    	  cal = Calendar.get((String)atrCal.getValue(0) );
      else
    	  cal = Calendar.getDefault();
      
    } catch (Exception e) {
      throw new IllegalArgumentException("Not a unit of time " + axis.getUnitsString());
    }

    int n = (int) axis.getSize();
    if (axis.isInterval()) {
      this.isInterval = true;
      this.bound1 = new double[n];
      this.bound2 = new double[n];
      double[] orgBound1 =  axis.getBound1();
      double[] orgBound2 =  axis.getBound2();
      this.bound2 = new double[n];
      for (int i = 0; i < axis.getSize(); i++) {
        this.bound1[i] = getValueInHours(unit, orgBound1[i]);
        this.bound2[i] = getValueInHours(unit, orgBound2[i]);
      }
    } else {
      offset = new double[n];
      for (int i = 0; i < axis.getSize(); i++) {
        offset[i] = getValueInHours(cal, unit, axis.getCoordValue(i));
      }
    }
  }

  
  double getValueInHours(Calendar cal, DateUnit unit, double value) {
	    //CalendarDate d = unit.makeCalendarDate(value);
	    //double secs =  unit.getTimeUnit().getValueInSeconds(value);	    	    	    
	    //CalendarDate d = CalendarDate.of(cal, unit.getDateOrigin().getTime() + (long)(1000*secs));
	    
	    CalendarDateUnit dateUnit = CalendarDateUnit.withCalendar(cal, unit.getUnitsString() ); // this will throw exception on failure
	    CalendarDate d = dateUnit.makeCalendarDate(value);
	    return FmrcInv.getOffsetInHours(runDate, d);
	  }  
  
  double getValueInHours(DateUnit unit, double value) {
    CalendarDate d = unit.makeCalendarDate(value);
    return FmrcInv.getOffsetInHours(runDate, d);
  }

  void addGridInventory(GridDatasetInv.Grid grid) {
    if (gridInv == null)
      gridInv = new ArrayList<>();
    gridInv.add(grid);
  }

  public CalendarDate getRunDate() {
    return runDate;
  }

  public boolean isInterval() {
    return isInterval;
  }

  /**
   * The list of GridDatasetInv.Grid that use this TimeCoord
   *
   * @return list of GridDatasetInv.Grid that use this TimeCoord
   */
  public List<GridDatasetInv.Grid> getGridInventory() {
    return (gridInv == null) ? new ArrayList<>() : gridInv;
  }

  /**
   * A unique id for this TimeCoord
   *
   * @return unique id for this TimeCoord
   */
  public int getId() {
    return id;
  }

  /**
   * Set the unique id for this TimeCoord
   *
   * @param id id for this TimeCoord
   */
  public void setId(int id) {
    this.id = id;
  }

  public String getName() {
    if (this == EMPTY) return "EMPTY";
    return id == 0 ? "time" : "time" + id;
  }

  public String getAxisName() {
    return axisName;
  }

  public int getNCoords() {
    return (isInterval) ? bound1.length : offset.length;
  }

   /**
   * The list of valid times, in units of hours since the run time
   * @return list of valid times, in units of hours since the run time
   */
  public double[] getOffsetTimes() {
    return isInterval ? bound2 : offset;
  }

  public double[] getBound1() {
    return bound1;
  }

  public double[] getBound2() {
    return bound2;
  }

  public void setOffsetTimes(double[] offset) {
    this.offset = offset;
  }

  public void setBounds(double[] bound1, double[] bound2) {
    this.bound1 = bound1;
    this.bound2 = bound2;
    this.isInterval = true;
  }

  public void setBounds(List<TimeCoord.Tinv> tinvs) {
    this.bound1 = new double[tinvs.size()];
    this.bound2 = new double[tinvs.size()];
    int count = 0;
    for (TimeCoord.Tinv tinv : tinvs) {
      this.bound1[count] = tinv.b1;
      this.bound2[count] = tinv.b2;
      count++;
    }
    this.isInterval = true;
  }

  @Override
  public String toString() {
    Formatter out = new Formatter();
    out.format("%-10s %-26s offsets=", getName(), runDate);
    if (isInterval)
      for (int i=0; i<bound1.length; i++) out.format((Locale) null, "(%3.1f,%3.1f) ", bound1[i], bound2[i]);
    else
      for (double val : offset) out.format((Locale) null, "%3.1f, ", val);
    return out.toString();
  }

  /**
   * Instances that have the same offsetHours/bounds and runtime are equal
   *
   * @param tother compare this TimeCoord's data
   * @return true if data are equal
   */
  public boolean equalsData(TimeCoord tother) {
    if (getRunDate() != null) {
      if (!getRunDate().equals(tother.getRunDate())) return false;
    }

    if (isInterval != tother.isInterval) return false;

    if (isInterval) {
      if (bound1.length != tother.bound1.length)
        return false;

      for (int i = 0; i < bound1.length; i++) {
        if (!ucar.nc2.util.Misc.nearlyEquals(bound1[i], tother.bound1[i]))
          return false;
        if (!ucar.nc2.util.Misc.nearlyEquals(bound2[i], tother.bound2[i]))
          return false;
      }
      return true;

    } else { // non interval

      if (offset.length != tother.offset.length)
        return false;

      for (int i = 0; i < offset.length; i++) {
        if (!ucar.nc2.util.Misc.nearlyEquals(offset[i], tother.offset[i]))
          return false;
      }
      return true;
    }
  }

  public int findInterval(double b1, double b2) {
    for (int i = 0; i < getNCoords(); i++)
      if (Misc.nearlyEquals(bound1[i], b1) && Misc.nearlyEquals(bound2[i], b2))
        return i;
    return -1;
  }

  public int findIndex(double offsetHour) {
    double[] off = getOffsetTimes();
    for (int i = 0; i < off.length; i++)
      if (Misc.nearlyEquals(off[i], offsetHour))
        return i;
    return -1;
  }

  public int compareTo(TimeCoord o) {
    return Integer.compare(id, o.id);
  }

  /////////////////////////////////////////////

  /**
   * Look through timeCoords to see if one matches want.
   * Matches means equalsData() is true.
   * If not found, make a new one and add to timeCoords.
   *
   * @param timeCoords look through this list
   * @param want find equivilent
   * @return return equivilent or make a new one and add to timeCoords
   */
  static public TimeCoord findTimeCoord(List<TimeCoord> timeCoords, TimeCoord want) {
    if (want == null) return null;

    for (TimeCoord tc : timeCoords) {
      if (want.equalsData(tc))
        return tc;
    }

    // make a new one
    TimeCoord result = new TimeCoord(want);
    timeCoords.add(result);
    return result;
  }

  /**
   * Create the union of all the values in the list of TimeCoord, ignoring the TimeCoord's runDate
   * @param timeCoords list of TimeCoord
   * @param baseDate resulting union timeCoord uses this as a base date
   * @return union TimeCoord
   */
  static public TimeCoord makeUnion(List<TimeCoord> timeCoords, CalendarDate baseDate) {
    if (timeCoords.size() == 0) return new TimeCoord(baseDate);
    if (timeCoords.size() == 1) return timeCoords.get(0);

    if (timeCoords.get(0).isInterval)
      return makeUnionIntv(timeCoords, baseDate);
    else
      return makeUnionReg(timeCoords, baseDate);
  }

  static private TimeCoord makeUnionReg(List<TimeCoord> timeCoords, CalendarDate baseDate) {
    // put into a set for uniqueness
    Set<Double> offsets = new HashSet<>();
    for (TimeCoord tc : timeCoords) {
      if (tc.isInterval)
        throw new IllegalArgumentException("Cant mix interval coordinates");
      for (double off : tc.getOffsetTimes())
        offsets.add(off);
    }

    // extract into a List and sort
    List<Double> offsetList = offsets.stream().sorted().collect(Collectors.toList());

    // extract into double[]
    double[] offset = new double[offsetList.size()];
    int count = 0;
    for (double off : offsetList)
      offset[count++] = off;

    // make the resulting time coord
    TimeCoord result = new TimeCoord(baseDate);
    result.setOffsetTimes(offset);
    return result;
  }

  static private TimeCoord makeUnionIntv(List<TimeCoord> timeCoords, CalendarDate baseDate) {
    // put into a set for uniqueness
    Set<Tinv> offsets = new HashSet<>();
    for (TimeCoord tc : timeCoords) {
      if (!tc.isInterval)
        throw new IllegalArgumentException("Cant mix non-interval coordinates");
      for (int i=0; i<tc.bound1.length; i++)
        offsets.add(new Tinv(tc.bound1[i], tc.bound2[i]));
    }

    // extract into a List and sort
    List<Tinv> bounds = offsets.stream().sorted().collect(Collectors.toList());

    // extract into double[] bounds arrays
    int n = bounds.size();
    double[] bounds1 = new double[n];
    double[] bounds2 = new double[n];
    for (int i=0; i<n; i++) {
      Tinv tinv = bounds.get(i);
      bounds1[i] = tinv.b1;
      bounds2[i] = tinv.b2;
    }

    // make the resulting time coord
    TimeCoord result = new TimeCoord(baseDate);
    result.setBounds(bounds1, bounds2);
    return result;
  }

  // use for matching intervals
  public static class Tinv implements Comparable<Tinv> {
    private double b1, b2;  // bounds

    public Tinv(double offset) {
      this.b2 = offset;
    }

    public Tinv(double b1, double b2) {
      this.b1 = b1;
      this.b2 = b2;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Tinv tinv = (Tinv) o;

      if (!Misc.nearlyEquals(b2, tinv.b2)) return false;
      return Misc.nearlyEquals(b1, tinv.b1);

    }

    @Override
    public int hashCode() {
      int result = (int) Math.round(b1 / Misc.defaultMaxRelativeDiffDouble);
      result = 31 * result + (int) Math.round(b2/Misc.defaultMaxRelativeDiffDouble);
      return result;
    }

    @Override
    public int compareTo(Tinv o) {
      boolean b1close = Misc.nearlyEquals(b1, o.b1);
      boolean b2close = Misc.nearlyEquals(b2, o.b2);
      if (b1close && b2close) return 0;
      if (b2close) return Double.compare(b1, o.b1);
      return Double.compare(b2, o.b2);
    }
  }

  /*
   * Create the union of all the values in the list of TimeCoord, converting all to a common baseDate
   * @param timeCoords list of TimeCoord
   * @param baseDate resulting union timeCoord uses this as a base date
   * @return union TimeCoord
   *
  static public TimeResult makeUnionConvert(List<TimeCoord> timeCoords, Date baseDate) {

    Map<Double, Double> offsetMap = new HashMap<Double, Double>(256);
    for (TimeCoord tc : timeCoords) {
      double run_offset = FmrcInv.getOffsetInHours(baseDate, tc.getRunDate());
      for (double offset : tc.getOffsetHours()) {
        offsetMap.put(run_offset + offset, run_offset); // later ones override
      }
    }

    Set<Double> keys = offsetMap.keySet();
    int n = keys.size();
    List<Double> offsetList = Arrays.asList((Double[]) keys.toArray(new Double[n]));
    Collections.sort(offsetList);

    int counto = 0;
    double[] offs = new double[n];
    double[] runoffs = new double[n];
    for (Double key : offsetList) {
      offs[counto] = key;
      runoffs[counto] = offsetMap.get(key);
      counto++;
    }

    return new TimeResult( baseDate, offs, runoffs);
  }

  static class TimeResult {
    double[] offsets;
    double[] runOffsets;
    Date base;

    TimeResult(Date base, double[] offsets, double[] runOffsets) {
      this.base = base;
      this.offsets = offsets;
      this.runOffsets = runOffsets;
    }
  } */


}

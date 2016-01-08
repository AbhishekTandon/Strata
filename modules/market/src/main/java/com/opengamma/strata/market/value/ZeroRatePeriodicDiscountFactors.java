/**
 * Copyright (C) 2015 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.strata.market.value;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.joda.beans.Bean;
import org.joda.beans.BeanBuilder;
import org.joda.beans.BeanDefinition;
import org.joda.beans.ImmutableBean;
import org.joda.beans.ImmutableConstructor;
import org.joda.beans.JodaBeanUtils;
import org.joda.beans.MetaProperty;
import org.joda.beans.Property;
import org.joda.beans.PropertyDefinition;
import org.joda.beans.impl.direct.DirectFieldsBeanBuilder;
import org.joda.beans.impl.direct.DirectMetaBean;
import org.joda.beans.impl.direct.DirectMetaProperty;
import org.joda.beans.impl.direct.DirectMetaPropertyMap;

import com.opengamma.strata.basics.currency.Currency;
import com.opengamma.strata.basics.date.DayCount;
import com.opengamma.strata.collect.ArgChecker;
import com.opengamma.strata.market.Perturbation;
import com.opengamma.strata.market.ValueType;
import com.opengamma.strata.market.curve.Curve;
import com.opengamma.strata.market.curve.CurveCurrencyParameterSensitivities;
import com.opengamma.strata.market.curve.CurveInfoType;
import com.opengamma.strata.market.curve.CurveName;
import com.opengamma.strata.market.curve.CurveUnitParameterSensitivities;
import com.opengamma.strata.market.curve.InterpolatedNodalCurve;
import com.opengamma.strata.market.sensitivity.ZeroRateSensitivity;

/**
 * Provides access to discount factors for a currency based on a zero rate periodically-compounded curve.
 * <p>
 * This provides discount factors for a single currency.
 * <p>
 * This implementation is based on an underlying curve that is stored with maturities
 * and zero-coupon periodically-compounded rates.
 */
@BeanDefinition(builderScope = "private")
public final class ZeroRatePeriodicDiscountFactors
    implements DiscountFactors, ImmutableBean, Serializable {

  /**
   * Year fraction used as an effective zero.
   */
  private static final double EFFECTIVE_ZERO = 1e-10;

  /**
   * The currency that the discount factors are for.
   */
  @PropertyDefinition(validate = "notNull", overrideGet = true)
  private final Currency currency;
  /**
   * The valuation date.
   */
  @PropertyDefinition(validate = "notNull", overrideGet = true)
  private final LocalDate valuationDate;
  /**
   * The underlying curve.
   * The metadata of the curve must define a day count.
   */
  @PropertyDefinition(validate = "notNull")
  private final Curve curve;
  /**
   * The number of compounding periods per year of the zero-coupon rate.
   */
  private final int frequency;  // cached, not a property
  /**
   * The day count convention of the curve.
   */
  private final DayCount dayCount;  // cached, not a property

  //-------------------------------------------------------------------------
  /**
   * Obtains an instance based on a zero-rates curve.
   * <p>
   * The curve is specified by an instance of {@link Curve}, such as {@link InterpolatedNodalCurve}.
   * The curve must contain {@linkplain ValueType#YEAR_FRACTION year fractions}
   * against {@linkplain ValueType#ZERO_RATE zero rates}.
   * The day count and compounding periods per year must be present in the metadata.
   * 
   * @param currency  the currency
   * @param valuationDate  the valuation date for which the curve is valid
   * @param underlyingCurve  the underlying curve
   * @return the curve
   */
  public static ZeroRatePeriodicDiscountFactors of(Currency currency, LocalDate valuationDate, Curve underlyingCurve) {
    return new ZeroRatePeriodicDiscountFactors(currency, valuationDate, underlyingCurve);
  }

  @ImmutableConstructor
  private ZeroRatePeriodicDiscountFactors(
      Currency currency,
      LocalDate valuationDate,
      Curve curve) {

    ArgChecker.notNull(currency, "currency");
    ArgChecker.notNull(valuationDate, "valuationDate");
    ArgChecker.notNull(curve, "curve");
    Optional<Integer> frequencyOpt = curve.getMetadata().findInfo(CurveInfoType.COMPOUNDING_PER_YEAR);
    ArgChecker.isTrue(frequencyOpt.isPresent(), "Compounding per year must be present for periodicaly compounded curve ");
    ArgChecker.isTrue(frequencyOpt.get() > 0, "Compounding per year must be positive");
    curve.getMetadata().getXValueType().checkEquals(
        ValueType.YEAR_FRACTION, "Incorrect x-value type for zero-rate discount curve");
    curve.getMetadata().getYValueType().checkEquals(
        ValueType.ZERO_RATE, "Incorrect y-value type for zero-rate discount curve");
    if (!curve.getMetadata().findInfo(CurveInfoType.DAY_COUNT).isPresent()) {
      throw new IllegalArgumentException("Incorrect curve metadata, missing DayCount");
    }
    this.currency = currency;
    this.valuationDate = valuationDate;
    this.curve = curve;
    this.dayCount = curve.getMetadata().getInfo(CurveInfoType.DAY_COUNT);
    this.frequency = frequencyOpt.get();
  }

  //-------------------------------------------------------------------------
  @Override
  public CurveName getCurveName() {
    return curve.getName();
  }

  @Override
  public int getParameterCount() {
    return curve.getParameterCount();
  }

  //-------------------------------------------------------------------------
  @Override
  public double discountFactor(LocalDate date) {
    double relativeYearFraction = relativeYearFraction(date);
    return discountFactor(relativeYearFraction);
  }

  @Override
  public double discountFactorWithSpread(
      LocalDate date,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodPerYear) {

    double yearFraction = relativeYearFraction(date);
    if (Math.abs(yearFraction) < EFFECTIVE_ZERO) {
      return 1d;
    }
    double df = discountFactor(date);
    if (compoundedRateType.equals(CompoundedRateType.PERIODIC)) {
      ArgChecker.notNegativeOrZero(periodPerYear, "periodPerYear");
      double ratePeriodicAnnualPlusOne =
          Math.pow(df, -1.0 / periodPerYear / yearFraction) + zSpread / periodPerYear;
      return Math.pow(ratePeriodicAnnualPlusOne, -periodPerYear * yearFraction);
    } else {
      return df * Math.exp(-zSpread * yearFraction);
    }
  }

  // calculates the discount factor at a given time
  private double discountFactor(double relativeYearFraction) {
    // convert zero rate periodically compounded to discount factor
    return Math.pow(1d + curve.yValue(relativeYearFraction) / frequency, -relativeYearFraction * frequency);
  }

  // calculate the relative time between the valuation date and the specified date
  private double relativeYearFraction(LocalDate date) {
    return dayCount.relativeYearFraction(valuationDate, date);
  }

  //-------------------------------------------------------------------------
  @Override
  public ZeroRateSensitivity zeroRatePointSensitivity(LocalDate date, Currency sensitivityCurrency) {
    double relativeYearFraction = relativeYearFraction(date);
    double discountFactor = discountFactor(relativeYearFraction);
    return ZeroRateSensitivity.of(currency, date, sensitivityCurrency, -discountFactor * relativeYearFraction);
  }

  @Override
  public ZeroRateSensitivity zeroRatePointSensitivityWithSpread(
      LocalDate date,
      Currency sensitivityCurrency,
      double zSpread,
      CompoundedRateType compoundedRateType,
      int periodPerYear) {

    double relativeYearFraction = relativeYearFraction(date);
    double discountFactor = discountFactorWithSpread(date, zSpread, compoundedRateType, periodPerYear);
    return ZeroRateSensitivity.of(currency, date, sensitivityCurrency, -discountFactor * relativeYearFraction);
  }

  //-------------------------------------------------------------------------
  @Override
  public CurveUnitParameterSensitivities unitParameterSensitivity(LocalDate date) {
    double relativeYearFraction = relativeYearFraction(date);
    return CurveUnitParameterSensitivities.of(curve.yValueParameterSensitivity(relativeYearFraction));
  }

  @Override
  public CurveCurrencyParameterSensitivities curveParameterSensitivity(ZeroRateSensitivity pointSensitivity) {
    CurveUnitParameterSensitivities sens = unitParameterSensitivity(pointSensitivity.getDate());
    return sens.multipliedBy(pointSensitivity.getCurrency(), pointSensitivity.getSensitivity());
  }

  //-------------------------------------------------------------------------
  @Override
  public ZeroRatePeriodicDiscountFactors applyPerturbation(Perturbation<Curve> perturbation) {
    return withCurve(curve.applyPerturbation(perturbation));
  }

  /**
   * Returns a new instance with a different curve.
   * 
   * @param curve  the new curve
   * @return the new instance
   */
  public ZeroRatePeriodicDiscountFactors withCurve(Curve curve) {
    return new ZeroRatePeriodicDiscountFactors(currency, valuationDate, curve);
  }

  //------------------------- AUTOGENERATED START -------------------------
  ///CLOVER:OFF
  /**
   * The meta-bean for {@code ZeroRatePeriodicDiscountFactors}.
   * @return the meta-bean, not null
   */
  public static ZeroRatePeriodicDiscountFactors.Meta meta() {
    return ZeroRatePeriodicDiscountFactors.Meta.INSTANCE;
  }

  static {
    JodaBeanUtils.registerMetaBean(ZeroRatePeriodicDiscountFactors.Meta.INSTANCE);
  }

  /**
   * The serialization version id.
   */
  private static final long serialVersionUID = 1L;

  @Override
  public ZeroRatePeriodicDiscountFactors.Meta metaBean() {
    return ZeroRatePeriodicDiscountFactors.Meta.INSTANCE;
  }

  @Override
  public <R> Property<R> property(String propertyName) {
    return metaBean().<R>metaProperty(propertyName).createProperty(this);
  }

  @Override
  public Set<String> propertyNames() {
    return metaBean().metaPropertyMap().keySet();
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the currency that the discount factors are for.
   * @return the value of the property, not null
   */
  @Override
  public Currency getCurrency() {
    return currency;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the valuation date.
   * @return the value of the property, not null
   */
  @Override
  public LocalDate getValuationDate() {
    return valuationDate;
  }

  //-----------------------------------------------------------------------
  /**
   * Gets the underlying curve.
   * The metadata of the curve must define a day count.
   * @return the value of the property, not null
   */
  public Curve getCurve() {
    return curve;
  }

  //-----------------------------------------------------------------------
  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (obj != null && obj.getClass() == this.getClass()) {
      ZeroRatePeriodicDiscountFactors other = (ZeroRatePeriodicDiscountFactors) obj;
      return JodaBeanUtils.equal(currency, other.currency) &&
          JodaBeanUtils.equal(valuationDate, other.valuationDate) &&
          JodaBeanUtils.equal(curve, other.curve);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int hash = getClass().hashCode();
    hash = hash * 31 + JodaBeanUtils.hashCode(currency);
    hash = hash * 31 + JodaBeanUtils.hashCode(valuationDate);
    hash = hash * 31 + JodaBeanUtils.hashCode(curve);
    return hash;
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder(128);
    buf.append("ZeroRatePeriodicDiscountFactors{");
    buf.append("currency").append('=').append(currency).append(',').append(' ');
    buf.append("valuationDate").append('=').append(valuationDate).append(',').append(' ');
    buf.append("curve").append('=').append(JodaBeanUtils.toString(curve));
    buf.append('}');
    return buf.toString();
  }

  //-----------------------------------------------------------------------
  /**
   * The meta-bean for {@code ZeroRatePeriodicDiscountFactors}.
   */
  public static final class Meta extends DirectMetaBean {
    /**
     * The singleton instance of the meta-bean.
     */
    static final Meta INSTANCE = new Meta();

    /**
     * The meta-property for the {@code currency} property.
     */
    private final MetaProperty<Currency> currency = DirectMetaProperty.ofImmutable(
        this, "currency", ZeroRatePeriodicDiscountFactors.class, Currency.class);
    /**
     * The meta-property for the {@code valuationDate} property.
     */
    private final MetaProperty<LocalDate> valuationDate = DirectMetaProperty.ofImmutable(
        this, "valuationDate", ZeroRatePeriodicDiscountFactors.class, LocalDate.class);
    /**
     * The meta-property for the {@code curve} property.
     */
    private final MetaProperty<Curve> curve = DirectMetaProperty.ofImmutable(
        this, "curve", ZeroRatePeriodicDiscountFactors.class, Curve.class);
    /**
     * The meta-properties.
     */
    private final Map<String, MetaProperty<?>> metaPropertyMap$ = new DirectMetaPropertyMap(
        this, null,
        "currency",
        "valuationDate",
        "curve");

    /**
     * Restricted constructor.
     */
    private Meta() {
    }

    @Override
    protected MetaProperty<?> metaPropertyGet(String propertyName) {
      switch (propertyName.hashCode()) {
        case 575402001:  // currency
          return currency;
        case 113107279:  // valuationDate
          return valuationDate;
        case 95027439:  // curve
          return curve;
      }
      return super.metaPropertyGet(propertyName);
    }

    @Override
    public BeanBuilder<? extends ZeroRatePeriodicDiscountFactors> builder() {
      return new ZeroRatePeriodicDiscountFactors.Builder();
    }

    @Override
    public Class<? extends ZeroRatePeriodicDiscountFactors> beanType() {
      return ZeroRatePeriodicDiscountFactors.class;
    }

    @Override
    public Map<String, MetaProperty<?>> metaPropertyMap() {
      return metaPropertyMap$;
    }

    //-----------------------------------------------------------------------
    /**
     * The meta-property for the {@code currency} property.
     * @return the meta-property, not null
     */
    public MetaProperty<Currency> currency() {
      return currency;
    }

    /**
     * The meta-property for the {@code valuationDate} property.
     * @return the meta-property, not null
     */
    public MetaProperty<LocalDate> valuationDate() {
      return valuationDate;
    }

    /**
     * The meta-property for the {@code curve} property.
     * @return the meta-property, not null
     */
    public MetaProperty<Curve> curve() {
      return curve;
    }

    //-----------------------------------------------------------------------
    @Override
    protected Object propertyGet(Bean bean, String propertyName, boolean quiet) {
      switch (propertyName.hashCode()) {
        case 575402001:  // currency
          return ((ZeroRatePeriodicDiscountFactors) bean).getCurrency();
        case 113107279:  // valuationDate
          return ((ZeroRatePeriodicDiscountFactors) bean).getValuationDate();
        case 95027439:  // curve
          return ((ZeroRatePeriodicDiscountFactors) bean).getCurve();
      }
      return super.propertyGet(bean, propertyName, quiet);
    }

    @Override
    protected void propertySet(Bean bean, String propertyName, Object newValue, boolean quiet) {
      metaProperty(propertyName);
      if (quiet) {
        return;
      }
      throw new UnsupportedOperationException("Property cannot be written: " + propertyName);
    }

  }

  //-----------------------------------------------------------------------
  /**
   * The bean-builder for {@code ZeroRatePeriodicDiscountFactors}.
   */
  private static final class Builder extends DirectFieldsBeanBuilder<ZeroRatePeriodicDiscountFactors> {

    private Currency currency;
    private LocalDate valuationDate;
    private Curve curve;

    /**
     * Restricted constructor.
     */
    private Builder() {
    }

    //-----------------------------------------------------------------------
    @Override
    public Object get(String propertyName) {
      switch (propertyName.hashCode()) {
        case 575402001:  // currency
          return currency;
        case 113107279:  // valuationDate
          return valuationDate;
        case 95027439:  // curve
          return curve;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
    }

    @Override
    public Builder set(String propertyName, Object newValue) {
      switch (propertyName.hashCode()) {
        case 575402001:  // currency
          this.currency = (Currency) newValue;
          break;
        case 113107279:  // valuationDate
          this.valuationDate = (LocalDate) newValue;
          break;
        case 95027439:  // curve
          this.curve = (Curve) newValue;
          break;
        default:
          throw new NoSuchElementException("Unknown property: " + propertyName);
      }
      return this;
    }

    @Override
    public Builder set(MetaProperty<?> property, Object value) {
      super.set(property, value);
      return this;
    }

    @Override
    public Builder setString(String propertyName, String value) {
      setString(meta().metaProperty(propertyName), value);
      return this;
    }

    @Override
    public Builder setString(MetaProperty<?> property, String value) {
      super.setString(property, value);
      return this;
    }

    @Override
    public Builder setAll(Map<String, ? extends Object> propertyValueMap) {
      super.setAll(propertyValueMap);
      return this;
    }

    @Override
    public ZeroRatePeriodicDiscountFactors build() {
      return new ZeroRatePeriodicDiscountFactors(
          currency,
          valuationDate,
          curve);
    }

    //-----------------------------------------------------------------------
    @Override
    public String toString() {
      StringBuilder buf = new StringBuilder(128);
      buf.append("ZeroRatePeriodicDiscountFactors.Builder{");
      buf.append("currency").append('=').append(JodaBeanUtils.toString(currency)).append(',').append(' ');
      buf.append("valuationDate").append('=').append(JodaBeanUtils.toString(valuationDate)).append(',').append(' ');
      buf.append("curve").append('=').append(JodaBeanUtils.toString(curve));
      buf.append('}');
      return buf.toString();
    }

  }

  ///CLOVER:ON
  //-------------------------- AUTOGENERATED END --------------------------
}
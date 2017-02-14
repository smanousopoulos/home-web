package eu.daiad.web.service.message.resolvers;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import eu.daiad.web.annotate.message.MessageGenerator;
import eu.daiad.web.model.ConsumptionStats.EnumStatistic;
import eu.daiad.web.model.EnumDayOfWeek;
import eu.daiad.web.model.EnumTimeAggregation;
import eu.daiad.web.model.EnumTimeUnit;
import eu.daiad.web.model.device.EnumDeviceType;
import eu.daiad.web.model.message.EnumRecommendationTemplate;
import eu.daiad.web.model.message.Message;
import eu.daiad.web.model.message.MessageResolutionStatus;
import eu.daiad.web.model.message.Recommendation.ParameterizedTemplate;
import eu.daiad.web.model.message.SimpleMessageResolutionStatus;
import eu.daiad.web.model.query.DataQuery;
import eu.daiad.web.model.query.DataQueryBuilder;
import eu.daiad.web.model.query.DataQueryResponse;
import eu.daiad.web.model.query.EnumDataField;
import eu.daiad.web.model.query.EnumMetric;
import eu.daiad.web.model.query.EnumMeasurementDataSource;
import eu.daiad.web.model.query.SeriesFacade;
import eu.daiad.web.service.ICurrencyRateService;
import eu.daiad.web.service.IDataService;
import eu.daiad.web.service.message.AbstractRecommendationResolver;

@MessageGenerator(period = "P1D")
@Component
@Scope("prototype")
public class InsightA1 extends AbstractRecommendationResolver
{
    public static class Parameters extends Message.AbstractParameters
        implements ParameterizedTemplate
    {
        /** A minimum value for daily volume consumption */
        private static final String MIN_VALUE = "1E-3"; 
        
        @NotNull
        @DecimalMin(MIN_VALUE)
        private Double currentValue;
        
        @NotNull
        @DecimalMin(MIN_VALUE)
        private Double averageValue;
        
        public Parameters()
        {}
        
        public Parameters(
            DateTime refDate, EnumDeviceType deviceType, double currentValue, double averageValue)
        {
            super(refDate, deviceType);
            this.averageValue = averageValue;
            this.currentValue = currentValue;
        }
        
        @JsonProperty("currentValue")
        public void setCurrentValue(double y)
        {
            this.currentValue = y;
        }
        
        @JsonProperty("currentValue")
        public Double getCurrentValue()
        {
            return currentValue;
        }

        @JsonProperty("averageValue")
        public void setAverageValue(double y)
        {
            this.averageValue = y;
        }
        
        @JsonProperty("averageValue")
        public Double getAverageValue()
        {
            return averageValue;
        }

        @JsonIgnore
        public EnumDayOfWeek getDayOfWeek()
        {
            return EnumDayOfWeek.valueOf(refDate.getDayOfWeek());   
        }

        @JsonIgnore
        @Override
        public EnumRecommendationTemplate getTemplate()
        {
            if (averageValue <= currentValue)
                return EnumRecommendationTemplate.INSIGHT_A1_DAYOFWEEK_CONSUMPTION_INCR;
            else 
                return EnumRecommendationTemplate.INSIGHT_A1_DAYOFWEEK_CONSUMPTION_DECR;
        }
        
        @JsonIgnore
        @Override
        public Map<String, Object> getParameters()
        {
            Map<String, Object> parameters = super.getParameters();
            
            parameters.put("value", currentValue);
            parameters.put("consumption", currentValue);     
            
            parameters.put("average_value", averageValue);
            parameters.put("average_consumption", averageValue);
            
            Double percentChange = 100.0 * Math.abs(((currentValue - averageValue) / averageValue));
            parameters.put("percent_change", Integer.valueOf(percentChange.intValue()));
          
            parameters.put("day", refDate.toDate());
            parameters.put("day_of_week", getDayOfWeek());
            
            return parameters;
        }
        
        @Override
        public Parameters withLocale(Locale target, ICurrencyRateService currencyRate)
        {
            return this;
        }
    }

    @Autowired
    IDataService dataService;
    
    @Override
    public List<MessageResolutionStatus<ParameterizedTemplate>> resolve(
        UUID accountKey, EnumDeviceType deviceType)
    {
        final double K = 1.28;  // a threshold (in units of standard deviation) of significant change
        final int N = 12;       // number of past weeks to examine
        final double F = 0.5;   // a threshold ratio of non-nulls for collected values
        final double dailyThreshold = config.getVolumeThreshold(deviceType, EnumTimeUnit.DAY);
        
        // Build a common part of a data-service query

        DataQuery query;
        DataQueryResponse queryResponse;
        SeriesFacade series;

        DataQueryBuilder queryBuilder = new DataQueryBuilder()
            .timezone(refDate.getZone())
            .user("user", accountKey)
            .source(EnumMeasurementDataSource.fromDeviceType(deviceType))
            .sum();

        // Compute for target day
        
        DateTime start = refDate.withTimeAtStartOfDay();
        
        query = queryBuilder
            .sliding(start, +1, EnumTimeUnit.DAY, EnumTimeAggregation.ALL)
            .build();
        queryResponse = dataService.execute(query);
        series = queryResponse.getFacade(deviceType);
        Double targetValue = (series != null)? 
            series.get(EnumDataField.VOLUME, EnumMetric.SUM) : null;
        if (targetValue == null || targetValue < dailyThreshold)
            return Collections.emptyList(); // nothing to compare to
        
        // Compute for past N weeks for a given day-of-week

        SummaryStatistics summary = new SummaryStatistics();
        for (int i = 0; i < N; i++) {
            start = start.minusWeeks(1);
            query = queryBuilder
                .sliding(start, +1, EnumTimeUnit.DAY, EnumTimeAggregation.ALL)
                .build();
            queryResponse = dataService.execute(query);
            series = queryResponse.getFacade(deviceType);
            Double val = (series != null)? 
                series.get(EnumDataField.VOLUME, EnumMetric.SUM) : null;
            if (val != null)
                summary.addValue(val);
        }
        if (summary.getN() < N * F)
            return Collections.emptyList(); // too few values
        
        // Seems we have sufficient data for the past weeks
        
        double averageValue = summary.getMean();
        if (averageValue < dailyThreshold)
            return Collections.emptyList(); // not reliable; consumption is too low

        double sd = Math.sqrt(summary.getPopulationVariance());
        double normValue = (sd > 0)? ((targetValue - averageValue) / sd) : Double.POSITIVE_INFINITY;
        double score = (sd > 0)? (Math.abs(normValue) / (2 * K)) : Double.POSITIVE_INFINITY;

        debug(
            "Insight A1 for account %s/%s: Consumption for %s of last %d weeks to %s:%n  " +
                "value=%.2f μ=%.2f σ=%.2f x*=%.2f score=%.2f",
             accountKey, deviceType, 
             refDate.toString("EEEE"), N, refDate.toString("dd/MM/YYYY"),
             targetValue, averageValue, sd, normValue, score);
        
        ParameterizedTemplate parameterizedTemplate = 
            new Parameters(refDate, deviceType, targetValue, averageValue);
        MessageResolutionStatus<ParameterizedTemplate> result = 
            new SimpleMessageResolutionStatus<>(score, parameterizedTemplate);
        return Collections.singletonList(result);
    }
}

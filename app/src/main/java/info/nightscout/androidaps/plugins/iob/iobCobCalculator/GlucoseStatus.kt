package info.nightscout.androidaps.plugins.iob.iobCobCalculator

import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.db.BgReading
import javax.inject.Inject
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.Round
import java.util.ArrayList

/**
 * Created by mike on 04.01.2017.
 */
class GlucoseStatus(
    val glucose: Double,
    val noise: Double,
    val delta: Double,
    val avgdelta: Double,
    val short_avgdelta: Double,
    val long_avgdelta: Double,
    val date: Long,
    val data: List<BgReading>?,
) {

    fun round() = GlucoseStatus(
        glucose = Round.roundTo(glucose, 0.1),
        noise = Round.roundTo(noise, 0.01),
        delta = Round.roundTo(delta, 0.01),
        avgdelta = Round.roundTo(avgdelta, 0.01),
        short_avgdelta = Round.roundTo(short_avgdelta, 0.01),
        long_avgdelta = Round.roundTo(long_avgdelta, 0.01),
        date = date,
        data = data,
    )

    override fun toString(): String {
        return "Glucose: " + DecimalFormatter.to0Decimal(glucose) + " mg/dl " +
            "Noise: " + DecimalFormatter.to0Decimal(noise) + " " +
            "Delta: " + DecimalFormatter.to0Decimal(delta) + " mg/dl" +
            "Short avg. delta: " + " " + DecimalFormatter.to2Decimal(short_avgdelta) + " mg/dl " +
            "Long avg. delta: " + DecimalFormatter.to2Decimal(long_avgdelta) + " mg/dl"
    }

    companion object {

        var cached: GlucoseStatus? = null

        fun average(array: ArrayList<Double>): Double {
            var sum = 0.0
            if (array.size == 0) return 0.0
            for (value in array) {
                sum += value
            }
            return sum / array.size
        }
    }

    class GlucoseStatusBuilder(injector: HasAndroidInjector) {

        @Inject
        lateinit var aapsLogger: AAPSLogger

        @Inject
        lateinit var iobCobCalculatorPlugin: IobCobCalculatorPlugin

        private var glucose = 0.0
        private var noise = 0.0
        private var delta = 0.0
        private var avgdelta = 0.0
        private var short_avgdelta = 0.0
        private var long_avgdelta = 0.0
        private var date = 0L
        private var data: List<BgReading>? = null

        init {
            injector.androidInjector().inject(this)
        }

        fun build(): GlucoseStatus {
            val status = GlucoseStatus(
                glucose = glucose,
                noise = noise,
                delta = delta,
                avgdelta = avgdelta, // for OpenAPS MA
                short_avgdelta = short_avgdelta,
                long_avgdelta = long_avgdelta,
                date = date,
                data = data,
            ).round()

            cached = status
            return status
        }

        fun getGlucoseStatusData(): GlucoseStatus? = getGlucoseStatusData(false)

        fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? {
            // load 45min
            //long fromtime = DateUtil.now() - 60 * 1000L * 45;
            //List<BgReading> data = MainApp.getDbHelper().getBgreadingsDataFromTime(fromtime, false);
            synchronized(iobCobCalculatorPlugin.dataLock) {
                data = iobCobCalculatorPlugin.bgReadings

                if (data?.equals(cached?.data) == true
                    && !(data!![0].date < DateUtil.now() - 7 * 60 * 1000L && !allowOldData)) {
                    aapsLogger.debug(LTag.GLUCOSE, "using cached GlucoseStatus")
                    return cached
                }

                if (data == null) {
                    aapsLogger.debug(LTag.GLUCOSE, "data=null")
                    return null
                }
                val sizeRecords = data!!.size
                if (sizeRecords == 0) {
                    aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==0")
                    return null
                }
                if (data!![0].date < DateUtil.now() - 7 * 60 * 1000L && !allowOldData) {
                    aapsLogger.debug(LTag.GLUCOSE, "olddata")
                    return null
                }
                val now = data!![0]
                val now_date = now.date
                var change: Double

                if (sizeRecords == 1) {
                    aapsLogger.debug(LTag.GLUCOSE, "sizeRecords==1")
                    return build()
                }

                val now_value_list = ArrayList<Double>()
                val last_deltas = ArrayList<Double>()
                val short_deltas = ArrayList<Double>()
                val long_deltas = ArrayList<Double>()

                // Use the latest sgv value in the now calculations
                now_value_list.add(now.value)
                for (i in 1 until sizeRecords) {
                    if (data!![i].value > 38) {
                        val then = data!![i]
                        val then_date = then.date
                        var avgdelta: Double
                        val minutesago: Long = Math.round((now_date - then_date) / (1000.0 * 60))
                        // multiply by 5 to get the same units as delta, i.e. mg/dL/5m
                        change = now.value - then.value
                        avgdelta = change / minutesago * 5
                        aapsLogger.debug(LTag.GLUCOSE, "$then minutesago=$minutesago avgdelta=$avgdelta")

                        // use the average of all data points in the last 2.5m for all further "now" calculations
                        if (0 < minutesago && minutesago < 2.5) {
                            // Keep and average all values within the last 2.5 minutes
                            now_value_list.add(then.value)
                            now.value = average(now_value_list)
                            // short_deltas are calculated from everything ~5-15 minutes ago
                        } else if (2.5 < minutesago && minutesago < 17.5) {
                            //console.error(minutesago, avgdelta);
                            short_deltas.add(avgdelta)
                            // last_deltas are calculated from everything ~5 minutes ago
                            if (2.5 < minutesago && minutesago < 7.5) {
                                last_deltas.add(avgdelta)
                            }
                            // long_deltas are calculated from everything ~20-40 minutes ago
                        } else if (17.5 < minutesago && minutesago < 42.5) {
                            long_deltas.add(avgdelta)
                        } else {
                            // Do not process any more records after >= 42.5 minutes
                            break
                        }
                    }
                }

                glucose = now.value
                date = now_date
                noise = 0.0 //for now set to nothing as not all CGMs report noise
                short_avgdelta = average(short_deltas)
                delta = if (last_deltas.isEmpty()) short_avgdelta else average(last_deltas)
                long_avgdelta = average(long_deltas)
                avgdelta = short_avgdelta // for OpenAPS MA

                val status = build()
                aapsLogger.debug(LTag.GLUCOSE, status.toString())
                return status.round()
            }
        }
    }
}
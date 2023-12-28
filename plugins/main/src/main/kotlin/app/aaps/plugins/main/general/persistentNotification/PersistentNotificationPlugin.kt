package app.aaps.plugins.main.general.persistentNotification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.extensions.apsAdjustedTargetMgdl
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.core.utils.DeferredForegroundStart
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("PrivatePropertyName", "DEPRECATION")
@Singleton
class PersistentNotificationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val activePlugins: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val rxBus: RxBus,
    private val context: Context,
    private val notificationHolder: NotificationHolder,
    private val dummyServiceHelper: DummyServiceHelper,
    private val iconsProvider: IconsProvider,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter,
    private val ch: ConcentrationHelper,
    private val loop: Loop,
    private val persistenceLayer: PersistenceLayer,
    private val processedDeviceStatusData: ProcessedDeviceStatusData,
    private val dateUtil: DateUtil,
    private val trendCalculator: TrendCalculator
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .pluginName(R.string.ongoingnotificaction)
        .enableByDefault(true)
        .alwaysEnabled(true)
        .showInList { false }
        .description(R.string.description_persistent_notification),
    aapsLogger, rh
) {

    // For Android Auto
    // End Android auto

    private val disposable = CompositeDisposable()
    private val deferredStart = DeferredForegroundStart()
    private var lastAutoNotificationContent: String = ""

    override suspend fun onStart() {
        super.onStart()
        notificationHolder.createNotificationChannel()
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventInitializationChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAutosensCalculationFinished::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
        /// Android Auto - debounced to prevent rapid pop-ups
        disposable += Observable.merge(
            rxBus.toObservable(EventRefreshOverview::class.java).map { },
            rxBus.toObservable(EventInitializationChanged::class.java).map { },
            rxBus.toObservable(EventAutosensCalculationFinished::class.java).map { }
        )
            .debounce(10, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate(includeAuto = true) }, fabricPrivacy::logException)
        /// End Android Auto
    }

    override suspend fun onStop() {
        disposable.clear()
        deferredStart.cancel()
        dummyServiceHelper.stopService(context)
        super.onStop()
    }

    private fun triggerNotificationUpdate(includeAuto: Boolean = false) {
        runBlocking { updateNotification(includeAuto) }
        deferredStart.start { dummyServiceHelper.startService(context) }
    }

    private suspend fun updateNotification(includeAuto: Boolean = false) {
        if (!config.appInitialized) return
        val pump = activePlugins.activePump
        var line1: String?
        var line2: String? = null
        var line3: String? = null
        var unreadConversationBuilder: NotificationCompat.CarExtender.UnreadConversation.Builder? = null
        if (profileFunction.isProfileValid("Notification")) {
            val lastBG = iobCobCalculator.ads.lastBg()
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            if (lastBG != null) {
                val trendSymbol = (trendCalculator.getTrendArrow(iobCobCalculator.ads)
                    ?.takeIf { it != TrendArrow.NONE } ?: TrendArrow.FLAT).symbol
                line1 = profileUtil.fromMgdlToStringInUnits(lastBG.recalculated) + " " + trendSymbol
                if (glucoseStatus != null) {
                    line1 += " " + profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                } else {
                    line1 += " " + rh.gs(R.string.old_data)
                }
            } else {
                line1 = rh.gs(app.aaps.core.ui.R.string.missed_bg_readings)
            }
            val activeTemp = processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())
            line1 += if (activeTemp != null) {
                " • " + activeTemp.toStringShort(rh) + " "
            } else {
                " • " + rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, ch.fromPump(pump.baseBasalRate)) + " "
            }
            val profileName = profileFunction.getProfileName()
            //IOB
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val cobInfo = iobCobCalculator.getCobInfo("PersistentNotificationPlugin")
            line2 =
                rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " + rh.gs(R.string.notification_iob_short, bolusIob.iob + basalIob.basaliob) + " • " + rh.gs(
                    app.aaps.core.ui.R
                        .string.cob
                ) + ": " + cobInfo.generateCOBString(decimalFormatter)
            line3 = profileName
            /// For Android Auto
            /// End Android Auto
        } else {
            line1 = rh.gs(app.aaps.core.ui.R.string.no_profile_set)
        }
        val content = "$line1|$line2|$line3"
        if (includeAuto && content == lastAutoNotificationContent) return
        if (includeAuto) lastAutoNotificationContent = content
        val builder = NotificationCompat.Builder(context, notificationHolder.channelID)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        builder.setSmallIcon(iconsProvider.getNotificationIcon())
        builder.setContentTitle(line1)
        if (line2 != null) builder.setContentText(line2)
        if (line3 != null) builder.setSubText(line3)
        /// Android Auto
        /// End Android Auto
        builder.setContentIntent(notificationHolder.openAppIntent(context))
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = builder.build()
        mNotificationManager.notify(notificationHolder.notificationID, notification)
        notificationHolder.notification = notification
    }
}

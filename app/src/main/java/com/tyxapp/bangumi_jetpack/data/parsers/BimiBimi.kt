package com.tyxapp.bangumi_jetpack.data.parsers

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import com.tyxapp.bangumi_jetpack.data.*
import com.tyxapp.bangumi_jetpack.data.db.AppDataBase
import com.tyxapp.bangumi_jetpack.main.home.adapter.BANNER
import com.tyxapp.bangumi_jetpack.player.danmakuparser.BiliDanmukuParser
import com.tyxapp.bangumi_jetpack.utilities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import master.flame.danmaku.danmaku.loader.android.DanmakuLoaderFactory
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import okhttp3.Request
import org.jetbrains.anko.collections.forEachWithIndex
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

private const val BASE_URL = "http://www.BimiBimi.tv"
private const val DANMU_URL = "http://119.23.209.33/static/danmu/dm"
private const val VIDEO_BASE_URL = "http://119.23.209.33/static/danmu/play.php?url="

class BimiBimi : IHomePageParser, IPlayerVideoParser, IsearchParser {


    private var homeDocument: Document? = null
    private lateinit var categorItems: ArrayList<CategorItem>

    private var playerDocument: Document? = null


    private val categorWords =
        arrayOf("百合", "青春", "后宫", "冒险", "运动", "科幻", "奇幻", "恋爱", "乙女", "少女", "热血")
    private val categorCoverUrl = arrayOf(
        "http://www.dilidili.name/uploads/allimg/180515/290_1534355771.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1537423811.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1535263451.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1720208191.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1538455201.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1536012441.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1537308891.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1536425281.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1717156631.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1536558011.jpg",
        "http://www.dilidili.name/uploads/allimg/180515/290_1537527161.jpg"
    )

    override suspend fun getBangumiDetail(id: String): BangumiDetail = withContext(Dispatchers.IO) {
        initPlayDocument(id)
        val parentElement = playerDocument!!.getElementsByClass("row")[0]
        val cover =
            parentElement.getElementsByClass("v_pic")[0].child(0).attr("data-original").run {
                if (!contains("http")) {
                    "$BASE_URL$this"
                } else {
                    this
                }
            }

        val titElement = parentElement.getElementsByClass("tit")[0]
        val name = titElement.getElementsByTag("h1").text()
        val ji = titElement.getElementsByTag("p").text()
        val cast = getAtagsText(parentElement.getElementsByClass("clearfix").getOrNull(2)).replace(
            " ",
            "\n"
        )
        val type = getAtagsText(parentElement.getElementsByClass("clearfix fn-left").getOrNull(0))
        val staff = getAtagsText(parentElement.getElementsByClass("clearfix fn-right").getOrNull(0))
        val niandai =
            parentElement.getElementsByClass("clearfix fn-right").getOrNull(1)?.text() ?: ""
        val intro = playerDocument!!.getElementsByClass("vod-jianjie").text()
        BangumiDetail(
            id = id,
            source = BangumiSource.BimiBimi,
            name = name,
            jiTotal = ji,
            niandai = niandai,
            cover = cover,
            cast = cast,
            type = type,
            staff = staff,
            intro = intro
        )
    }

    private fun getAtagsText(element: Element?) = buildString {
        element?.also {
            append(it.text())
            append(" ")
        }
    }

    override suspend fun getJiList(id: String): Pair<Int, List<JiItem>> =
        withContext(Dispatchers.IO) {
            initPlayDocument(id)
            var line = 0
            val jiItems = ArrayList<JiItem>()
            playerDocument!!.getElementsByClass("play_box").forEach {
                it.child(0).children().forEachWithIndex { index, jiElement ->
                    if (jiItems.getOrNull(index) == null) {
                        jiItems.add(JiItem(jiElement.text()))
                    }
                }
                line++
            }
            line to jiItems
        }

    override suspend fun getPlayerUrl(id: String, ji: Int, line: Int): VideoUrl =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/bangumi/$id/play/${line + 1}/${ji + 1}/"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", PHONE_REQUEST)
                    .build()
                val document = Jsoup.parse(OkhttpUtil.getResponseData(request))
                val jsonData =
                    document.getElementsByClass("leo-player")[0].child(0).toString().run {
                        substring(indexOf("{"), lastIndexOf("}") + 1)
                    }
                val jsonObject = JSONObject(jsonData)
                val videoUrl: String = jsonObject.getString("url").run {
                    return@run if (contains("%")) {
                        parserBase64Url(this)
                    } else {
                        parserBimiEncodeUrl(this)
                    }
                }
                VideoUrl(videoUrl)
            } catch (e: Exception) {
                e.printStackTrace()
                VideoUrl("$BASE_URL/bangumi/bi/$id/", true)
            }
        }

    private fun parserBase64Url(base64Url: String): String {
        val vUrl = URLDecoder.decode(base64Url, "utf-8")
        return if (!vUrl.contains("http")) {
            val baseUrl = "http://119.23.209.33/static/danmu"
            val purl = if (vUrl.contains(".mp4")) {
                "$baseUrl/niux.php?id=$vUrl"
            } else {
                "$baseUrl/bit.php?$vUrl"
            }
            val videoUrlHtmlDocument = Jsoup.parse(OkhttpUtil.getResponseData(purl))
            videoUrlHtmlDocument.getElementById("video").child(0).attrSrc()
        } else {
            vUrl
        }
    }

    private fun parserBimiEncodeUrl(encodeUrl: String): String {
        val playUrl = "$VIDEO_BASE_URL$encodeUrl"
        val document = Jsoup.parse(OkhttpUtil.getResponseData(playUrl))
        return document.getElementsByTag("source").get(0).attrSrc()
    }

    override fun getSearchResult(searchWord: String): Listing<Bangumi> {
        val factor = BimiSearchResultDataSourceFactor(searchWord)
        val pagelist = LivePagedListBuilder(factor, 8).build()
        return Listing(
            liveDataPagelist = pagelist,
            initialLoad = factor.dataSourceLiveData.switchMap { it.initialLoadLiveData },
            netWordState = factor.dataSourceLiveData.switchMap { it.netWordState },
            retry = { factor.dataSourceLiveData.value?.retry() }
        )
    }

    override suspend fun getRecommendBangumis(id: String): List<Bangumi> =
        withContext(Dispatchers.IO) {
            initPlayDocument(id)
            val bangumis = ArrayList<Bangumi>()
            playerDocument!!.getElementsByClass("love-det").getOrNull(0)
                ?.getElementsByClass("item")
                ?.forEach { bangumis.add(parserBangumi(it)) }
            bangumis
        }

    override suspend fun getDanmakuParser(id: String, ji: Int): BaseDanmakuParser? =
        withContext(Dispatchers.IO) {
            val url = "$DANMU_URL/$id/$id-${ji + 1}.php"
            val loader = DanmakuLoaderFactory.create(DanmakuLoaderFactory.TAG_BILI)
            loader.load(OkhttpUtil.getResponseBody(url).byteStream())
            BiliDanmukuParser().apply { load(loader.dataSource) }
        }


    override suspend fun getHomeBangumis(): Map<String, List<Bangumi>> =
        withContext(Dispatchers.IO) {
            initHomeDocument()
            val homebangumis = LinkedHashMap<String, List<Bangumi>>()

            val bannerData = parseBanner()
            homebangumis[bannerData.first] = bannerData.second

            homeDocument!!.getElementsByClass("area-cont").forEach {
                val data = parserHomeData(it)
                homebangumis[data.first] = data.second
            }

            homebangumis
        }

    private fun parserHomeData(element: Element): Pair<String, ArrayList<Bangumi>> {
        val title: String = element.getElementsByTag("b")[0].text()
        val bangumis = ArrayList<Bangumi>()
        element.getElementsByClass("item").take(6).forEach { bangumiElement ->
            bangumis.add(parserBangumi(bangumiElement))
        }
        return title to bangumis
    }

    /**
     * 轮播图
     *
     */
    private fun parseBanner(): Pair<String, List<Bangumi>> {
        val bannerBnagumis = ArrayList<Bangumi>()
        homeDocument!!.getElementsByClass("banner-box")[0]
            .getElementsByClass("item").forEach { bangumiElement ->
                val id = parserid(bangumiElement.attrHref())!!
                val name = bangumiElement.attr("title")
                val cover = bangumiElement.get_img_tags()[0].attrSrc()
                bannerBnagumis.add(Bangumi(id, BangumiSource.BimiBimi, name, cover))
            }
        return BANNER to bannerBnagumis
    }

    override suspend fun getCategorItems(): List<CategorItem> {
        if (!::categorItems.isInitialized) {
            categorItems = ArrayList()
            categorCoverUrl.forEachIndexed { index, url ->
                categorItems.add(CategorItem(url, categorWords[index]))
            }
        }
        return categorItems
    }

    override fun getCategoryBangumis(category: String): Listing<CategoryBangumi> {
        val factory = BimiCategoryResultDataSourceFactor(category)
        val paging = LivePagedListBuilder(factory, 12).build()
        return Listing(
            liveDataPagelist = paging,
            initialLoad = factory.dataSourceLiveData.switchMap { it.initialLoadLiveData },
            netWordState = factory.dataSourceLiveData.switchMap { it.netWordState },
            retry = { factory.dataSourceLiveData.value?.retry() }
        )
    }

    override suspend fun getBangumiTimeTable(): List<List<Bangumi>> = withContext(Dispatchers.IO) {
        initHomeDocument()
        val weekBangumis = ArrayList<List<Bangumi>>()
        homeDocument!!.getElementsByClass("tab-content").forEach { dayBnagumisElement ->
            val dayBangumis = ArrayList<Bangumi>()
            dayBnagumisElement.getElementsByClass("bangumi-item").forEach { bangumiElement ->
                val infoElement = bangumiElement.getElementsByClass("item-info")[0]
                val id = parserid(infoElement.get_a_tags()[0].attrHref())!!
                val name = infoElement.get_a_tags()[0].text()
                val jiTotal = infoElement.getElementsByTag("span").text()
                val cover = bangumiElement.getElementsByTag("img")[0].attrSrc().run {
                    if (!contains("http")) {
                        "$BASE_URL$this"
                    } else {
                        this
                    }
                }
                dayBangumis.add(Bangumi(id, BangumiSource.BimiBimi, name, cover, jiTotal))
            }
            weekBangumis.add(dayBangumis)
        }
        weekBangumis
    }

    private fun initHomeDocument() {
        if (homeDocument == null) {
            synchronized(this) {
                homeDocument = Jsoup.parse(OkhttpUtil.getResponseData(BASE_URL))
            }
        }
    }

    private fun initPlayDocument(id: String) {
        if (playerDocument == null) {
            synchronized(this) {
                playerDocument =
                    Jsoup.parse(OkhttpUtil.getResponseData("$BASE_URL/bangumi/bi/$id/"))
            }
        }
    }

}

/**
 * <header_img_light class="item"><a href="/bangumi/bi/2127/" title="无节操☆Bitch社" target="_blank"class="img">
<img class="lazy" data-original="https://wxt.sinaimg.cn/large/006MDjU7ly1g9o2o2vxoxj30ak0extaa.jpg" src="/template/bimibimi_pc/images/grey.png" alt="无节操☆Bitch社" width="170" height="224"/><span class="mask"><p>导演：</p><i class="iconfont icon-play"></i></span></a>
<div class="info">
<a href="/bangumi/bi/2127/" title="无节操☆Bitch社"target="_blank">无节操☆Bitch社</a>
<p><span class="fl">全2话</span></p>
</div>
</header_img_light>
 */
private fun parserBangumi(bangumiElement: Element): Bangumi {
    val infoElement = bangumiElement.getElementsByClass("info")[0]
    val id = parserid(infoElement.get_a_tags()[0].attrHref())!!
    val name = infoElement.get_a_tags()[0].text()
    val jiTotal = infoElement.getElementsByTag("span")[0].text()
    var cover = bangumiElement.get_img_tags()[0].attr("data-original")
    if (!cover.contains("http")) {
        cover = BASE_URL + cover
    }
    return Bangumi(id, BangumiSource.BimiBimi, name, cover, jiTotal)
}

private fun parserid(idStr: String) = Regex("""\d+""").find(idStr)?.value

private class BimiSearchResultDataSourceFactor(
    private val searchWord: String
) : DataSource.Factory<Int, Bangumi>() {
    val dataSourceLiveData = MutableLiveData<BimiSearchResultDataSource>()

    override fun create(): DataSource<Int, Bangumi> {
        return BimiSearchResultDataSource(searchWord).apply {
            dataSourceLiveData.postValue(this)
        }
    }

}

private class BimiSearchResultDataSource(
    searchWord: String
) : PageResultDataSourch<Int, Bangumi>(searchWord) {
    override fun initialLoad(
        params: LoadInitialParams<Int>,
        callback: LoadInitialCallback<Int, Bangumi>
    ) {
        val page = 1
        val url = "$BASE_URL/vod/search/wd/$encodeSearchWord/page$page/"
        val document = Jsoup.parse(OkhttpUtil.getResponseData(url))
        val result = parserSearchBangumis(document)

        callback.onResult(result, null, if (result.isEmpty()) null else page + 1)
        initialLoadLiveData.postValue(InitialLoad(NetWordState.SUCCESS, result.isEmpty()))
    }

    override fun afterload(params: LoadParams<Int>, callback: LoadCallback<Int, Bangumi>) {
        val page = params.key
        val url = "$BASE_URL/vod/search/wd/$encodeSearchWord/page/$page/"
        val document = Jsoup.parse(OkhttpUtil.getResponseData(url))
        val result = parserSearchBangumis(document)

        callback.onResult(result, if (result.isEmpty()) null else page + 1)
    }

    private fun parserSearchBangumis(document: Document): List<Bangumi> {
        val bangumis = ArrayList<Bangumi>()
        document.getElementsByClass("v_tb")[0].getElementsByClass("item").forEach {
            bangumis.add(parserBangumi(it))
        }
        return bangumis
    }

}

private class BimiCategoryResultDataSource(
    private val categoryWord: String
) : PageResultDataSourch<Int, CategoryBangumi>(categoryWord) {
    private val bangumiDao = AppDataBase.getInstance().bangumiDetailDao()

    override fun initialLoad(
        params: LoadInitialParams<Int>,
        callback: LoadInitialCallback<Int, CategoryBangumi>
    ) {
        val page = 1
        val categoryKey = getCategoryKey(categoryWord)
        val url = if (categoryKey == null) {
            "$BASE_URL/vodshow/fanzu---$encodeSearchWord-----$page---"
        } else {
            "$BASE_URL/type/$categoryKey-$page/"
        }
        val result = parserCategoryBagnumis(Jsoup.parse(OkhttpUtil.getResponseData(url)))
        callback.onResult(result, null, if (result.isEmpty()) null else page + 1)
        initialLoadLiveData.postValue(InitialLoad(NetWordState.SUCCESS, result.isEmpty()))
    }

    override fun afterload(params: LoadParams<Int>, callback: LoadCallback<Int, CategoryBangumi>) {
        val page = params.key
        val categoryKey = getCategoryKey(categoryWord)
        val url = if (categoryKey == null) {
            "$BASE_URL/vodshow/fanzu---$encodeSearchWord-----$page---"
        } else {
            "$BASE_URL/type/$categoryKey-$page/"
        }
        val result = parserCategoryBagnumis(Jsoup.parse(OkhttpUtil.getResponseData(url)))
        callback.onResult(result, if (result.isEmpty()) null else page + 1)
    }

    private fun parserCategoryBagnumis(document: Document): List<CategoryBangumi> {
        val categoryBangumis = ArrayList<CategoryBangumi>()
        document.getElementsByClass("v_tb")[0].getElementsByClass("item").forEach {
            val bangumi = parserBangumi(it)
            val isFollow = bangumiDao.isFollowingBangumi(bangumi.id, bangumi.source.name)
            categoryBangumis.add(
                CategoryBangumi(
                    bangumi.id,
                    bangumi.name,
                    bangumi.source,
                    bangumi.cover,
                    isFollow = isFollow
                )
            )
        }
        return categoryBangumis
    }

    /**
     * 获取拼接Url的类别词
     *
     */
    private fun getCategoryKey(categoryWord: String): String? {
        return when (categoryWord) {
            "新番放送" -> "riman"
            "国产动漫" -> "guoman"
            "番组计划" -> "fanzu"
            "剧场动画" -> "juchang"
            "影视" -> "move"
            else -> null
        }
    }

}

private class BimiCategoryResultDataSourceFactor(
    private val categoryWord: String
) : DataSource.Factory<Int, CategoryBangumi>() {
    val dataSourceLiveData = MutableLiveData<BimiCategoryResultDataSource>()

    override fun create(): DataSource<Int, CategoryBangumi> {
        return BimiCategoryResultDataSource(categoryWord).apply {
            dataSourceLiveData.postValue(this)
        }
    }

}
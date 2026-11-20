package com.moying.app

/** 仅此文件维护示例内容；Screen 仅通过 Repository 读取，不内嵌片单。 */
object MockData {
    val categories = listOf("推荐", "电影", "剧集", "动漫")
    val movies = listOf(
        Movie("long-season", "漫长的季节", "剧集", "2023", "中国大陆", "剧情 · 悬疑", "9.4", "全12集", "long-season.jpg", "long-season.jpg", "一座北方小城，一场跨越多年的追寻。被时间掩埋的真相，与普通人的命运再次交错。", 12, 3_600_000L),
        Movie("dune", "沙丘2", "电影", "2024", "美国", "科幻 · 冒险", "8.2", "电影 · 完整版", "dune.jpg", "player-backdrop.jpg", "保罗与弗雷曼人并肩作战，在沙漠星球上寻找属于自己的道路。命运与选择的边界，正在风沙中浮现。", 1, 9_960_000L),
        Movie("interstellar", "星际穿越", "电影", "2014", "美国", "科幻 · 冒险", "9.4", "电影 · 完整版", "interstellar.jpg", "interstellar.jpg", "一支探险队穿越虫洞，踏上超越人类经验的星际旅程。当时间成为距离，爱是他们回家的坐标。", 1, 10_140_000L),
        Movie("severance", "人生切割术", "剧集", "2022", "美国", "科幻 · 悬疑", "9.1", "全9集", "severance.webp", "severance-backdrop.webp", "一项将工作记忆与生活记忆分离的手术，让卢蒙公司的员工过上两种人生。完美秩序背后，隐藏着怎样的秘密？", 9, 3_000_000L),
        Movie("blossoms", "繁花", "剧集", "2023", "中国大陆", "剧情 · 都市", "8.7", "全30集", "blossoms.webp", "blossoms.webp", "九十年代的上海，时代浪潮裹挟着每个人。灯火明灭之间，是生意，也是人生。", 30, 2_700_000L),
        Movie("oppenheimer", "奥本海默", "电影", "2023", "美国", "剧情 · 传记", "8.8", "电影 · 完整版", "oppenheimer.jpg", "oppenheimer.jpg", "一个科学家与时代的碰撞，一次改变世界的选择，以及无法回避的内心回响。", 1, 10_800_000L),
        Movie("past-lives", "过往人生", "电影", "2023", "美国", "剧情 · 爱情", "7.7", "电影 · 完整版", "past-lives.jpg", "past-lives.jpg", "分隔两地的儿时好友，在多年后重逢。时间与距离，重新定义了他们之间未说出口的情感。", 1, 6_360_000L),
        Movie("grand-budapest", "布达佩斯大饭店", "电影", "2014", "美国", "剧情 · 喜剧", "8.9", "电影 · 完整版", "grand-budapest.jpg", "grand-budapest.jpg", "一位传奇礼宾员与年轻门童，卷入一场围绕名画和家族遗产展开的离奇冒险。", 1, 5_940_000L),
        Movie("inception", "盗梦空间", "电影", "2010", "美国", "科幻 · 悬疑", "9.4", "电影 · 完整版", "inception.jpg", "inception.jpg", "一位能潜入梦境的窃贼，接受了一项几乎不可能的任务：在人类意识最深处种下一颗念头。", 1, 8_880_000L),
        Movie("spirited-away", "千与千寻", "动漫", "2001", "日本", "动画 · 奇幻", "9.4", "电影 · 完整版", "spirited-away.jpg", "spirited-away.jpg", "少女千寻误入神灵世界，为了救回父母，她在奇妙的汤屋里学会勇敢、告别与成长。", 1, 7_500_000L),
        Movie("wandering-earth", "流浪地球2", "电影", "2023", "中国大陆", "科幻 · 冒险", "8.3", "电影 · 完整版", "wandering-earth.webp", "wandering-earth.webp", "面对太阳危机，人类决定带着地球离开太阳系。无数普通人，为共同的明天作出选择。", 1, 10_380_000L),
        Movie("dark-glory", "黑暗荣耀", "剧集", "2022", "韩国", "剧情 · 悬疑", "8.9", "全16集", "glory.webp", "glory.webp", "受过伤害的女孩长大后，用漫长而缜密的计划，直面那些曾经改变她命运的人。", 16, 3_000_000L),
    )
    val initialHistory = listOf(
        WatchHistory("severance", 3, 1_122_000L, 3_000_000L, 1_793_520_000_000L),
        WatchHistory("interstellar", 1, 3_420_000L, 10_140_000L, 1_793_433_600_000L),
        WatchHistory("long-season", 4, 1_080_000L, 3_600_000L, 1_793_347_200_000L),
    )
}

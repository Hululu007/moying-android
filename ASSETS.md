# 墨映 · 图片资源清单

全部图片已下载到本地 `assets/`，原型运行时不依赖远程图片服务。以下仅为界面展示资源；片单、评分、更新状态与播放进度由 `MockData.js` 提供。

| 文件路径 | 画面内容 | 图片来源 |
| --- | --- | --- |
| assets/dune.jpg | 沙丘2 · 英文海报 | [TMDB 图片](https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg) |
| assets/interstellar.jpg | 星际穿越 · 英文海报 | [TMDB 图片](https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg) |
| assets/oppenheimer.jpg | 奥本海默 · 英文海报 | [TMDB 图片](https://image.tmdb.org/t/p/w500/ptpr0kGAckfQkJeJIt8st5dglvd.jpg) |
| assets/past-lives.jpg | 过往人生 · 英文海报 | [影片官方网站](https://tickets.pastlives.movie/) · [原图](https://dx35vtwkllhj9.cloudfront.net/a24films/past-lives/images/regions/us/onesheet.jpg) |
| assets/grand-budapest.jpg | 布达佩斯大饭店 · 海报 | [Filmadatbázis 影片页](https://filmadatbazis.hu/film/a-grand-budapest-hotel/) · [TMDB 图片](https://image.tmdb.org/t/p/w500/fEHXKrdNjtf5R7YAA0ssVZLJLOa.jpg) |
| assets/inception.jpg | 盗梦空间 · 英文海报 | [TMDB 图片](https://image.tmdb.org/t/p/w500/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg) |
| assets/spirited-away.jpg | 千与千寻 · 中文海报 | [1905电影网](https://www.1905.com/mdb/film/2114237/) · [原图](https://image13.m1905.cn/mdb/uploadfile/2019/0524/20190524013700133933.jpg) |
| assets/wandering-earth.webp | 流浪地球2 · 中文海报 | [欧洲时报 / Chine-info](https://www.chine-info.com/static/content/french/RegardsurlaChine/Culture/2023-03-01/1083141519638814720.html) · [原图](https://www.oushinet.com/image/2023-03-08/thumb/1083146379151040512.jpeg) |
| assets/severance.webp | 人生切割术 · 英文海报 | [TMDB 图片](https://image.tmdb.org/t/p/w500/pPHpeI2X1qEd1CS1SeyrdhZ4qnT.jpg) |
| assets/long-season.jpg | 漫长的季节 · 中文海报 | [Mrkni.tv 影片页](https://mrkni.tv/serial/the-long-season/) · [TMDB 图片](https://image.tmdb.org/t/p/w500/xErvw04IuhNx5OyESipIGbiDvdX.jpg) |
| assets/blossoms.webp | 繁花 · 海报 | [ACMODASI 影片页](https://www.acmodasi.pro/amdb/tv/106841-blossoms-shanghai-2023) · [原图](https://www.acmodasi.pro/amdb/images/tv/w640/10/68/blossoms-shanghai-2023-1547739.webp) |
| assets/glory.webp | 黑暗荣耀 · 英文海报 | [Pasundan Ekspres 文章](https://pasundan.jabarekspres.com/2023/01/03/drama-korea-the-glory/) · [原图](https://assets.jabarekspres.com/pasundan/2023/01/THE-GLORY.png) |
| assets/player-backdrop.jpg | 沙丘2 · 16:9 静态剧照 | [Filmadatbázis 影片页](https://filmadatbazis.hu/film/dune-masodik-resz/) · [TMDB 图片](https://image.tmdb.org/t/p/w1280/eZ239CUp1d6OryZEBPnO2n87gMG.jpg) |
| assets/severance-backdrop.webp | 人生切割术 · 16:9 静态剧照 | [ACMODASI 影片页](https://www.acmodasi.pro/amdb/tv/95396-severance-2022) · [原图](https://www.acmodasi.pro/amdb/images/tv/w1920/9/53/severance-2022-1286269.webp) |

## 本地处理

海报保留原图构图，部分缩小至最大宽度 640 px，并转为 JPEG / WebP 以减小体积；原型通过 `object-fit: cover` 统一展示比例。宽幅图片是播放器的静态预览画面，模拟控制不会将它变成实际视频。Android 工程如复用图片，应复制这里的最终资源版本。

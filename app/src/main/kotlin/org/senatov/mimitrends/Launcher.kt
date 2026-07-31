package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    LoggerFactory.getLogger("Launcher").debug(LogTag.APP, "main(args={})", args.contentToString())
    App.main(args)
}

package jobs

import play.api.inject.SimpleModule
import play.api.inject._

class ImportJobModule extends SimpleModule(bind[Import].toSelf.eagerly())
class ExportJobModule extends SimpleModule(bind[ExportStream].toSelf.eagerly())

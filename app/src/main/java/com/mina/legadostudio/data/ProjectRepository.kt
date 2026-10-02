package com.mina.legadostudio.data

import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.data.db.SourceRevisionEntity
import com.mina.legadostudio.data.db.StudioDao
import kotlinx.coroutines.flow.Flow

class ProjectRepository(private val dao: StudioDao) {
    companion object {
        const val MAX_DOMAIN_VERSIONS = 5 // 限制同一域名下最多保留 5 个版本，防止模型过度备份刷满书源库
    }

    fun observe(): Flow<List<ProjectEntity>> = dao.observeProjects()
    suspend fun list(): List<ProjectEntity> = dao.allProjects()
    suspend fun get(id: String): ProjectEntity? = dao.project(id)
    suspend fun save(value: ProjectEntity): ProjectEntity {
        val old = dao.project(value.id)
        val next = value.copy(updatedAt = System.currentTimeMillis())
        dao.saveProject(next)
        if (old?.sourceJson != next.sourceJson) dao.addRevision(SourceRevisionEntity(projectId = next.id, sourceJson = next.sourceJson, note = next.stage))
        trimDomainVersions(next.siteUrl, MAX_DOMAIN_VERSIONS)
        return next
    }
    suspend fun delete(ids: List<String>): Int = if (ids.isEmpty()) 0 else dao.deleteProjects(ids.distinct())
    suspend fun export(id: String): String = dao.project(id)?.sourceJson ?: error("项目不存在")

    private suspend fun trimDomainVersions(siteUrl: String, keep: Int) {
        runCatching {
            val domain = com.mina.legadostudio.domain.SourceCatalog.domainOf(siteUrl)
            val all = dao.allProjects()
            val sameDomain = all.filter { com.mina.legadostudio.domain.SourceCatalog.domainOf(it.siteUrl) == domain }
                .sortedByDescending { it.updatedAt }
            if (sameDomain.size > keep) {
                val toDelete = sameDomain.drop(keep).map { it.id }
                dao.deleteProjects(toDelete)
            }
        }
    }
}

import { ref } from 'vue'
import { getTaskStatus, setTaskStatus } from '../api/aiImage'
import type { TaskStatus } from '../types'

// 全局任务状态 Map: productDir|task → status
const _taskMap = ref<Record<string, TaskStatus>>({})

function _key(productDir: string, task: string) {
  return productDir + '|' + task
}

export function useTaskStatus() {
  /**
   * 获取任务状态（优先本地缓存，没有再请求后端）
   */
  async function getStatus(productDir: string, task: string): Promise<TaskStatus> {
    const k = _key(productDir, task)
    if (_taskMap.value[k]) return _taskMap.value[k]
    try {
      const data = await getTaskStatus(productDir, task)
      if (data.status && data.status !== 'none') {
        _taskMap.value[k] = data.status
      }
      return data.status || 'none'
    } catch {
      return 'none'
    }
  }

  /**
   * 设置任务状态（本地 + 后端）
   */
  async function updateStatus(productDir: string, task: string, status: TaskStatus) {
    const k = _key(productDir, task)
    _taskMap.value[k] = status
    try {
      await setTaskStatus(productDir, task, status)
    } catch {}
  }

  /**
   * 清除任务状态
   */
  function clearStatus(productDir: string, task: string) {
    const k = _key(productDir, task)
    delete _taskMap.value[k]
  }

  return { getStatus, updateStatus, clearStatus }
}

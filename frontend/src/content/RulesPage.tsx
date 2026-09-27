import type { ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FormEvent, useState } from 'react'
import { ApiClientError } from '../api/client'
import { getRules, updateRules } from '../api/content'
import type { RulesBlock } from '../api/types'

export function RulesPage() {
  const qc = useQueryClient()
  const query = useQuery({ queryKey: ['rules'], queryFn: getRules })
  const [editing, setEditing] = useState(false)
  const save = useMutation({
    mutationFn: ({ title, content }: { title: string; content: string }) => updateRules(title, content),
    onSuccess: async () => {
      setEditing(false)
      await qc.invalidateQueries({ queryKey: ['rules'] })
    },
  })

  if (query.isPending) return <main className="page-shell"><Status>Загружаем правила...</Status></main>
  if (query.isError) return <main className="page-shell"><Status error>Не удалось загрузить правила.</Status></main>
  const data = query.data.data

  if (editing && data.manageable) {
    return <main className="page-shell">
      <button className="back-link text-button" onClick={() => setEditing(false)}>Назад к правилам</button>
      <RulesEditor title={data.title} content={data.editableContent ?? ''} pending={save.isPending} error={save.error} onSave={(title, content) => save.mutate({ title, content })} />
    </main>
  }

  return <main className="page-shell rules-page" id="rules-top">
    <div className="section-heading">
      <div><p className="eyebrow">Правила игры</p><h1>{data.title}</h1></div>
      {data.manageable && <button className="button secondary" onClick={() => setEditing(true)}>Редактировать</button>}
    </div>
    <div className="rules-layout">
      <aside className="rules-sidebar">
        <nav className="rules-toc" aria-label="Оглавление правил">
          <p className="rules-toc-title">Навигация по правилам</p>
          <div className="rules-toc-list">
            {data.document.sections.map((section) => <div className="rules-toc-group" key={section.anchor}>
              <a className="rules-toc-section" href={`#${section.anchor}`}>{section.title}</a>
              {section.navigation.length > 0 && <div className="rules-toc-sub">
                {section.navigation.map((item) => <a key={item.anchor} href={`#${item.anchor}`}>{item.title}</a>)}
              </div>}
            </div>)}
          </div>
          <a className="rules-back-top" href="#rules-top">Наверх</a>
        </nav>
      </aside>
      <div className="rules-document">
        {data.document.introLines.length > 0 && <section className="rules-intro" aria-label="Введение">
          {data.document.introLines.map((line, index) => <p className={index === 0 ? 'rules-brand' : index === 1 ? 'rules-document-title' : 'rules-meta'} key={index}>{line}</p>)}
        </section>}
        {data.document.sections.map((section) => <section className="rules-section" id={section.anchor} key={section.anchor}>
          <h2>{section.title}</h2>
          {section.blocks.map((block, index) => <RuleBlock key={`${block.anchor}-${index}`} block={block} />)}
        </section>)}
      </div>
    </div>
  </main>
}

function RuleBlock({ block }: { block: RulesBlock }) {
  if (block.type === 'article') return <h3 className="rules-article" id={block.anchor}>{block.label}</h3>
  if (block.type === 'subheading') return <h4 className="rules-subheading" id={block.anchor}>{block.label}</h4>
  if (block.type === 'clause') return <p className="rule-clause"><strong>{block.label}</strong><span>{block.text}</span></p>
  if (block.type === 'definition') return <p className="rule-definition"><strong>{block.label}</strong><span>{block.text}</span></p>
  if (block.type === 'bullet') return <p className="rule-bullet"><span className="rule-bullet-mark" aria-hidden="true">•</span><span>{block.text}</span></p>
  if (block.type === 'continuation') return <p className="rule-continuation"><span className="rule-number-spacer" aria-hidden="true"></span><span>{block.text}</span></p>
  return <p className="rule-paragraph">{block.text}</p>
}

function RulesEditor({ title, content, pending, error, onSave }: { title: string; content: string; pending: boolean; error: unknown; onSave: (t: string, c: string) => void }) {
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    onSave(String(form.get('title') ?? '').trim(), String(form.get('content') ?? ''))
  }
  return <form className="form-card" onSubmit={submit}>
    <h1>Редактировать правила</h1>
    {error != null && <Status error>{error instanceof ApiClientError ? error.details.message : 'Не удалось сохранить правила.'}</Status>}
    <label className="field"><span>Заголовок</span><input name="title" maxLength={160} required defaultValue={title} /></label>
    <label className="field"><span>Исходный текст</span><textarea name="content" rows={28} maxLength={150000} required defaultValue={content} /></label>
    <button className="button primary" disabled={pending}>Сохранить правила</button>
  </form>
}

function Status({ children, error = false }: { children: ReactNode; error?: boolean }) {
  return <section className={`status-panel${error ? ' error-panel' : ''}`}>{children}</section>
}

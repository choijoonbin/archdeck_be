import { Router, Request, Response } from 'express';
import { db } from '../db.js';
import { GlossaryTerm } from '../types/index.js';

export const glossaryRouter = Router();

// GET /api/glossary
glossaryRouter.get('/', (req: Request, res: Response) => {
  const { category, search } = req.query;
  let terms = db.getGlossary();

  if (category && typeof category === 'string' && category !== 'ALL') {
    terms = terms.filter(t => t.category.toLowerCase() === category.toLowerCase());
  }

  if (search && typeof search === 'string') {
    const q = search.toLowerCase();
    terms = terms.filter(t => 
      t.term.toLowerCase().includes(q) ||
      t.koreanName.toLowerCase().includes(q) ||
      t.shortDef.toLowerCase().includes(q) ||
      t.tags.some(tag => tag.toLowerCase().includes(q))
    );
  }

  res.json(terms);
});

// GET /api/glossary/:id
glossaryRouter.get('/:id', (req: Request, res: Response) => {
  const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;
  const term = db.getGlossaryById(id);
  if (!term) {
    return res.status(404).json({ error: '해당 용어를 찾을 수 없습니다.' });
  }
  res.json(term);
});

// POST /api/glossary
glossaryRouter.post('/', (req: Request, res: Response) => {
  const newTerm: GlossaryTerm = req.body;
  if (!newTerm.term || !newTerm.shortDef) {
    return res.status(400).json({ error: '필수 필드(term, shortDef)가 누락되었습니다.' });
  }

  if (!newTerm.id) {
    newTerm.id = `term-${Date.now()}`;
  }

  const saved = db.addGlossary(newTerm);
  res.status(201).json(saved);
});

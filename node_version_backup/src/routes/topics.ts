import { Router, Request, Response } from 'express';
import { db } from '../db.js';
import { Topic } from '../types/index.js';

export const topicsRouter = Router();

// GET /api/topics
topicsRouter.get('/', (req: Request, res: Response) => {
  const { category, search } = req.query;
  let topics = db.getTopics();

  if (category && typeof category === 'string') {
    topics = topics.filter(t => t.category.toLowerCase() === category.toLowerCase());
  }

  if (search && typeof search === 'string') {
    const q = search.toLowerCase();
    topics = topics.filter(t => 
      t.title.toLowerCase().includes(q) || 
      t.koreanTitle.toLowerCase().includes(q) ||
      t.summary.toLowerCase().includes(q) ||
      t.badges.some(b => b.toLowerCase().includes(q))
    );
  }

  res.json(topics);
});

// GET /api/topics/:id
topicsRouter.get('/:id', (req: Request, res: Response) => {
  const id = Array.isArray(req.params.id) ? req.params.id[0] : req.params.id;
  const topic = db.getTopicById(id);
  if (!topic) {
    return res.status(404).json({ error: '해당 아키텍처 토픽을 찾을 수 없습니다.' });
  }
  res.json(topic);
});

// POST /api/topics
topicsRouter.post('/', (req: Request, res: Response) => {
  const newTopic: Topic = req.body;
  if (!newTopic.title || !newTopic.koreanTitle) {
    return res.status(400).json({ error: '필수 필드(title, koreanTitle)가 누락되었습니다.' });
  }

  if (!newTopic.id) {
    newTopic.id = `topic-${Date.now()}`;
  }

  const saved = db.addTopic(newTopic);
  res.status(201).json(saved);
});
